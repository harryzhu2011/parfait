package com.custardsource.parfait.pcp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;

import org.apache.log4j.Logger;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedResource;

import com.custardsource.parfait.Monitor;
import com.custardsource.parfait.Monitorable;
import com.custardsource.parfait.MonitorableRegistry;
import com.custardsource.parfait.MonitoringView;
import com.custardsource.parfait.ValueSemantics;
import com.custardsource.parfait.dxm.MetricName;
import com.custardsource.parfait.dxm.PcpWriter;
import com.custardsource.parfait.dxm.semantics.Semantics;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;

/**
 * PcpMonitorBridge bridges between the set of {@link Monitorable}s in the current system and a PCP
 * monitor agent. The bridge works by persisting any changes to a Monitorable into a section of
 * memory that is also mapped into the PCP monitor agents address space.
 */
@ManagedResource
public class PcpMonitorBridge extends MonitoringView {

    private static final Logger LOG = Logger.getLogger(PcpMonitorBridge.class);
    
    private static final TextSource DEFAULT_SHORT_TEXT_SOURCE = new MetricDescriptionTextSource();
    private static final TextSource DEFAULT_LONG_TEXT_SOURCE = new EmptyTextSource();

    public static final int UPDATE_QUEUE_SIZE = 1024;

    private static final Map<ValueSemantics, Semantics> SEMANTICS_MAP = ImmutableMap.of(
            ValueSemantics.CONSTANT, Semantics.DISCRETE, ValueSemantics.FREE_RUNNING,
            Semantics.INSTANT, ValueSemantics.MONOTONICALLY_INCREASING, Semantics.COUNTER);

    private final ArrayBlockingQueue<Monitorable<?>> monitorablesPendingUpdate = new ArrayBlockingQueue<Monitorable<?>>(
    		UPDATE_QUEUE_SIZE);

    private final Monitor monitor = new PcpMonitorBridgeMonitor();
    private final Thread updateThread;
    private final MetricNameMapper mapper;
    private final TextSource shortTextSource;
    private final TextSource longTextSource;

    /*
     * Determines whether value changes detected are written out to an external file for external
     * monitoring by the PCP agent.
     */
    private boolean outputValuesToPCPFile = true;

	private volatile PcpWriter pcpWriter;



    public PcpMonitorBridge(PcpWriter writer) {
    	this(writer, MonitorableRegistry.DEFAULT_REGISTRY);
    }

    public PcpMonitorBridge(PcpWriter writer, MonitorableRegistry registry) {
        this(writer, registry, MetricNameMapper.PASSTHROUGH_MAPPER, DEFAULT_SHORT_TEXT_SOURCE,
                DEFAULT_LONG_TEXT_SOURCE);
    }
    
    public PcpMonitorBridge(PcpWriter writer, MonitorableRegistry registry,
            MetricNameMapper mapper, TextSource shortTextSource, TextSource longTextSource) {
		super(registry);
		this.pcpWriter = Preconditions.checkNotNull(writer);
		this.mapper = Preconditions.checkNotNull(mapper);
        this.shortTextSource = Preconditions.checkNotNull(shortTextSource);
        this.longTextSource = Preconditions.checkNotNull(longTextSource);
		this.updateThread = new Thread(new Updater());
		this.updateThread.setName("PcpMonitorBridge-Updater");
		this.updateThread.setDaemon(true);
    }

    @Override
    public void stopMonitoring(Collection<Monitorable<?>> monitorables) {
        pcpWriter = null;
        for (Monitorable<?> monitorable : monitorables) {
            monitorable.removeMonitor(monitor);
        }
    }

    public boolean hasUpdatesPending() {
        return monitorablesPendingUpdate.size() > 0;
    }

    @Override
    protected void startMonitoring(Collection<Monitorable<?>> monitorables) {
        try {
            for (Monitorable<?> monitorable : monitorables) {
            	monitorable.attachMonitor(monitor);
            	MetricName metricName = mapper.map(monitorable.getName());
                pcpWriter.addMetric(metricName,
                        convertToPcpSemantics(monitorable.getSemantics()), monitorable.getUnit(),
                        monitorable.get());
                pcpWriter.setMetricHelpText(metricName.getMetric(), shortTextSource.getText(
                        monitorable, metricName), longTextSource.getText(monitorable, metricName));
            }
            pcpWriter.start();

            updateThread.start();

            LOG.info("PCP monitoring bridge started for writer [" + pcpWriter + "]");
        } catch (IOException e) {
            throw new RuntimeException("Unable to initialise PCP monitoring bridge", e);
        }
    }

    private Semantics convertToPcpSemantics(ValueSemantics semantics) {
        return SEMANTICS_MAP.get(semantics);
    }

    /**
     * The Updater is responsible for taking any Monitorables that are pending in the update queue
     * and saving their current value to the PCP shared data file.
     */
    private class Updater implements Runnable {

        public void run() {
            try {
                Collection<Monitorable<?>> monitorablesToUpdate = new ArrayList<Monitorable<?>>();
                PcpWriter writerCopy;
                while ((writerCopy = pcpWriter) != null) {
                    try {
                        monitorablesToUpdate.add(monitorablesPendingUpdate.take());
                        monitorablesPendingUpdate.drainTo(monitorablesToUpdate);
                        for (Monitorable<?> monitorable : monitorablesToUpdate) {
							writerCopy.updateMetric(mapper.map(monitorable
									.getName()), monitorable.get());
                        }
                        if (monitorablesPendingUpdate.size() >= UPDATE_QUEUE_SIZE) {
                            LOG.warn("Update queue was full - some updates may have been lost.");
                        }
                        monitorablesToUpdate.clear();
                    } catch (InterruptedException e) {
                        LOG.error("Updater was unexpectedly interrupted", e);
                    }
                }
            } catch (RuntimeException e) {
                LOG.fatal("Updater dying because of unexpected exception", e);
                throw e;
            } catch (Error e) {
                LOG.fatal("Updater dying because of unexpected exception", e);
                throw e;
            }
        }
    }

    /**
     * Responsible for adding any Monitorables that change to the queue of Monitorables that are
     * pending update. This class will never block, if the update queue is ever full then the we
     * just do nothing.
     */
    private class PcpMonitorBridgeMonitor implements Monitor {

        public void valueChanged(Monitorable<?> monitorable) {
            /*
             * If the master-arm switch to output values to a file is off, then abandon quickly. The
             * only reason it would be turned off is because we have suspected it is causing
             * performance grief. Highly unlikely, but just in case.
             */
            if (!isOutputValuesToPCPFile()) {
                return;
            }

            if (!monitorablesPendingUpdate.offer(monitorable)) {
            	// Don't care about return value here. If this failed, the queue must be full;
            	// This will get detected by the Updater and logged. We should do nothing here as we 
            	// don't want to block.
            }
        }
    }

    @ManagedAttribute(description = "If set, value changes are written to an external file monitored PCP Agent.")
    public boolean isOutputValuesToPCPFile() {
        return outputValuesToPCPFile;
    }

    @ManagedAttribute
    public void setOutputValuesToPCPFile(boolean outputValuesToPCPFile) {
        this.outputValuesToPCPFile = outputValuesToPCPFile;
    }
}
