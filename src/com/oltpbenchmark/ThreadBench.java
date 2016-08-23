/******************************************************************************
 *  Copyright 2015 by OLTPBenchmark Project                                   *
 *                                                                            *
 *  Licensed under the Apache License, Version 2.0 (the "License");           *
 *  you may not use this file except in compliance with the License.          *
 *  You may obtain a copy of the License at                                   *
 *                                                                            *
 *    http://www.apache.org/licenses/LICENSE-2.0                              *
 *                                                                            *
 *  Unless required by applicable law or agreed to in writing, software       *
 *  distributed under the License is distributed on an "AS IS" BASIS,         *
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  *
 *  See the License for the specific language governing permissions and       *
 *  limitations under the License.                                            *
 ******************************************************************************/


package com.oltpbenchmark;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Set;

import org.apache.commons.collections15.map.ListOrderedMap;
import org.apache.log4j.Logger;

import com.oltpbenchmark.LatencyRecord.Sample;
import com.oltpbenchmark.api.TransactionType;
import com.oltpbenchmark.api.Worker;
import com.oltpbenchmark.types.State;
import com.oltpbenchmark.util.EarlyAbortConfiguration;
import com.oltpbenchmark.util.Histogram;
import com.oltpbenchmark.util.QueueLimitException;
import com.oltpbenchmark.util.StringUtil;

public class ThreadBench implements Thread.UncaughtExceptionHandler {
    private static final Logger LOG = Logger.getLogger(ThreadBench.class);

    
    private static BenchmarkState testState;
    private final List<? extends Worker> workers;
    private final ArrayList<Thread> workerThreads;
    // private File profileFile;
    private List<WorkloadConfiguration> workConfs;
    private List<WorkloadState> workStates;
    ArrayList<LatencyRecord.Sample> samples = new ArrayList<LatencyRecord.Sample>();
    private int intervalMonitor = 0;
    private EarlyAbortConfiguration earlyAbortConfig = null;

    private ThreadBench(List<? extends Worker> workers, List<WorkloadConfiguration> workConfs) {
        this(workers, null, workConfs);
    }

    public ThreadBench(List<? extends Worker> workers, File profileFile, List<WorkloadConfiguration> workConfs) {
        this.workers = workers;
        this.workConfs = workConfs;
        this.workerThreads = new ArrayList<Thread>(workers.size());
    }

    public static final class TimeBucketIterable implements Iterable<DistributionStatistics> {
        private final Iterable<Sample> samples;
        private final int windowSizeSeconds;
        private final TransactionType txType;

        /**
         * @param samples
         * @param windowSizeSeconds
         * @param txType
         *            Allows to filter transactions by type
         */
        public TimeBucketIterable(Iterable<Sample> samples, int windowSizeSeconds, TransactionType txType) {
            this.samples = samples;
            this.windowSizeSeconds = windowSizeSeconds;
            this.txType = txType;
        }

        @Override
        public Iterator<DistributionStatistics> iterator() {
            return new TimeBucketIterator(samples.iterator(), windowSizeSeconds, txType);
        }
    }

    public static final class TimeBucketIterator implements Iterator<DistributionStatistics> {
        private final Iterator<Sample> samples;
        private final int windowSizeSeconds;
        private final TransactionType txType;

        private Sample sample;
        private long nextStartNs;

        private DistributionStatistics next;

        /**
         * @param samples
         * @param windowSizeSeconds
         * @param txType
         *            Allows to filter transactions by type
         */
        public TimeBucketIterator(Iterator<LatencyRecord.Sample> samples, int windowSizeSeconds, TransactionType txType) {
            this.samples = samples;
            this.windowSizeSeconds = windowSizeSeconds;
            this.txType = txType;

            if (samples.hasNext()) {
                sample = samples.next();
                // TODO: To be totally correct, we would want this to be the
                // timestamp of the start
                // of the measurement interval. In most cases this won't matter.
                nextStartNs = sample.startNs;
                calculateNext();
            }
        }

        private void calculateNext() {
            assert next == null;
            assert sample != null;
            assert sample.startNs >= nextStartNs;

            // Collect all samples in the time window
            ArrayList<Integer> latencies = new ArrayList<Integer>();
            long endNs = nextStartNs + windowSizeSeconds * 1000000000L;
            while (sample != null && sample.startNs < endNs) {

                // Check if a TX Type filter is set, in the default case,
                // INVALID TXType means all should be reported, if a filter is
                // set, only this specific transaction
                if (txType == TransactionType.INVALID || txType.getId() == sample.tranType)
                    latencies.add(sample.latencyUs);

                if (samples.hasNext()) {
                    sample = samples.next();
                } else {
                    sample = null;
                }
            }

            // Set up the next time window
            assert sample == null || endNs <= sample.startNs;
            nextStartNs = endNs;

            int[] l = new int[latencies.size()];
            for (int i = 0; i < l.length; ++i) {
                l[i] = latencies.get(i);
            }

            next = DistributionStatistics.computeStatistics(l);
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public DistributionStatistics next() {
            if (next == null)
                throw new NoSuchElementException();
            DistributionStatistics out = next;
            next = null;
            if (sample != null) {
                calculateNext();
            }
            return out;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("unsupported");
        }
    }

    private void createWorkerThreads() {

        for (Worker worker : workers) {
            worker.initializeState();
            Thread thread = new Thread(worker);
            thread.setUncaughtExceptionHandler(this);
            thread.start();
            this.workerThreads.add(thread);
        }
        return;
    }

    private void interruptWorkers() {
        for (Worker worker : workers) {
            worker.cancelStatement();
        }
    }

    private int finalizeWorkers(ArrayList<Thread> workerThreads) throws InterruptedException {
        assert testState.getState() == State.DONE || testState.getState() == State.EXIT;
        int requests = 0;

        new WatchDogThread().start();

        for (int i = 0; i < workerThreads.size(); ++i) {

            // FIXME not sure this is the best solution... ensure we don't hang
            // forever, however we might ignore 
            // problems
            workerThreads.get(i).join(60000); // wait for 60second for threads
                                              // to terminate... hands otherwise

            /*
             * // CARLO: Maybe we might want to do this to kill threads that are
             * hanging... if (workerThreads.get(i).isAlive()) {
             * workerThreads.get(i).kill(); try { workerThreads.get(i).join(); }
             * catch (InterruptedException e) { } }
             */

            requests += workers.get(i).getRequests();
            workers.get(i).tearDown(false);
        }
        testState = null;
        return requests;
    }

    private class WatchDogThread extends Thread {
        {
            this.setDaemon(true);
        }

        @Override
        public void run() {
            Map<String, Object> m = new ListOrderedMap<String, Object>();
            LOG.info("Starting WatchDogThread");
            while (true) {
                try {
                    Thread.sleep(20000);
                } catch (InterruptedException ex) {
                    return;
                }
                if (testState == null)
                    return;
                m.clear();
                for (Thread t : workerThreads) {
                    m.put(t.getName(), t.isAlive());
                }
                LOG.info("Worker Thread Status:\n" + StringUtil.formatMaps(m));
            } // WHILE
        }
    } // CLASS

    private class EarlyAbortThread extends Thread {
        private final EarlyAbortConfiguration abortConfig;
        private final int intervalSeconds;
        private double averageLatency;
        
        {
            this.setDaemon(true);
        }
        EarlyAbortThread(EarlyAbortConfiguration abortConfig) {
            this.abortConfig = abortConfig;
            this.intervalSeconds = abortConfig.getIntervalSeconds();
            this.averageLatency = 0.0;
        }
        @Override
        public void run() {
            LOG.info("Starting EarlyAbortThread Interval[" + this.intervalSeconds + " seconds]");
            while (true) {
                try {
                    Thread.sleep(this.intervalSeconds * 1000);
                } catch (InterruptedException ex) {
                    return;
                }

                Phase phase = null;
                int numLatencies = 0;
                int chunkSize = 1000;
                int capacity = chunkSize;
                int[] latencies = new int[capacity];
                synchronized (testState) {
                    if (testState == null) {
                        return;
                    }
                    for (WorkloadState workState : workStates) {
                        synchronized (workState) {
                            phase = workState.getCurrentPhase();
                            if (phase == null) {
                                return;
                            }
                        }
                    }
                    for (Worker w : workers) {
                        List<Integer> wLatencies = w.getAndResetRawLatencies();
                        LOG.info("NUM_LATENCIES = " + wLatencies.size());
                        for (int lat : wLatencies) {
                            if (numLatencies >= capacity) {
                                capacity += chunkSize;
                                latencies = Arrays.copyOf(latencies, capacity);
                            }
                            latencies[numLatencies++] = lat;
                        }
                    }
                }
                if (capacity > numLatencies) {
                    latencies = Arrays.copyOfRange(latencies, 0, numLatencies);
                }
                
                double latencyThreshold = 0.0;
                if (phase.isLatencyRun()) {
                    int responseTimesElapsed = latencies.length;
                    double currentLatency = 0.0;
                    for (int i = 0; i < responseTimesElapsed; ++i) {
                        currentLatency += latencies[i];
                    }
                    this.averageLatency = currentLatency;
                    latencyThreshold = abortConfig.getLatencyThreshold(responseTimesElapsed);
                } else {
                    DistributionStatistics stats = DistributionStatistics.computeStatistics(latencies);
                    double currentLatency = 0.0;
                    switch (abortConfig.getLatencyMetric()) {
                        case MAXIMUM:
                            currentLatency = stats.getMaximum();
                            break;
                        case PERCENTILE_99TH:
                            currentLatency = stats.get99thPercentile();
                            break;
                        case PERCENTILE_95TH:
                            currentLatency = stats.get95thPercentile();
                            break;
                        case PERCENTILE_90TH:
                            currentLatency = stats.get90thPercentile();
                            break;
                        case PERCENTILE_75TH:
                            currentLatency = stats.get75thPercentile();
                            break;
                        case MEDIAN:
                            currentLatency = stats.getMedian();
                            break;
                        case PERCENTILE_25TH:
                            currentLatency = stats.get25thPercentile();
                            break;
                        case MEAN:
                            currentLatency = stats.getAverage();
                            break;
                        case MINIMUM:
                            currentLatency = stats.getMinimum();
                            break;
                        default:
                            LOG.error("FATAL: unexpected latency metric " 
                                    + abortConfig.getLatencyMetric());
                            System.exit(-1);
                    }
                    if (this.averageLatency == 0.0) {
                        this.averageLatency = currentLatency;
                    } else {
                        this.averageLatency = (currentLatency + averageLatency) / 2.0;
                    }
                    latencyThreshold = abortConfig.getLatencyThreshold();
                    
                }
                LOG.info("abort threshold: " + abortConfig.getLatencyThreshold());
                LOG.info("WLATENCY: " + averageLatency);
                if (this.averageLatency > latencyThreshold) {
                    // TODO: abort! And print out stats collected so far.
                    LOG.info("ABORTING!!");
                    synchronized (testState) {
                        if (phase.isLatencyRun()) {
                            testState.ackLatencyComplete();
                        }
                        for (WorkloadState workState : workStates) {
                            synchronized (workState) {
                                workState.switchToNextPhase();
                                phase = workState.getCurrentPhase();
                                interruptWorkers();
                                // Last phase
                                testState.startCoolDown();
                                LOG.info("[EarlyAbort] Waiting for all terminals to finish ..");
                            }
                        }
                    }
                }
            } // WHILE
        }
    } // CLASS

    private class MonitorThread extends Thread {
        private final int intervalMonitor;
        {
            this.setDaemon(true);
        }
        MonitorThread(int interval) {
            this.intervalMonitor = interval;
        }
        @Override
        public void run() {
            LOG.info("Starting MonitorThread Interval[" + this.intervalMonitor + " seconds]");
            while (true) {
                try {
                    Thread.sleep(this.intervalMonitor * 1000);
                } catch (InterruptedException ex) {
                    return;
                }
                if (testState == null)
                    return;
                // Compute the last throughput
                long measuredRequests = 0;
                synchronized (testState) {
                    for (Worker w : workers) {
                        measuredRequests += w.getAndResetIntervalRequests();
                    }
                }
                double tps = (double) measuredRequests / (double) this.intervalMonitor;
                LOG.info("Throughput: " + tps + " Tps");
            } // WHILE
        }
    } // CLASS
    
    /*
     * public static Results runRateLimitedBenchmark(List<Worker> workers, File
     * profileFile) throws QueueLimitException, IOException { ThreadBench bench
     * = new ThreadBench(workers, profileFile); return
     * bench.runRateLimitedFromFile(); }
     */

    public static Results runRateLimitedBenchmark(List<Worker> workers,
            List<WorkloadConfiguration> workConfs,
            int intervalMonitoring,
            EarlyAbortConfiguration abortConfig) throws QueueLimitException, IOException {
        ThreadBench bench = new ThreadBench(workers, workConfs);
        bench.intervalMonitor = intervalMonitoring;
        bench.earlyAbortConfig = abortConfig;
        return bench.runRateLimitedMultiPhase();
    }

    public Results runRateLimitedMultiPhase() throws QueueLimitException, IOException {
        assert testState == null;
        testState = new BenchmarkState(workers.size() + 1);
        workStates = new ArrayList<WorkloadState>();

        for (WorkloadConfiguration workState : this.workConfs) {
            workStates.add(workState.initializeState(testState));
        }

        this.createWorkerThreads();
        testState.blockForStart();

        // long measureStart = start;

        long start = System.nanoTime();
        long measureEnd = -1;
        long now = -1;
        // used to determine the longest sleep interval
        int lowestRate = Integer.MAX_VALUE;

        Phase phase = null;

        for (WorkloadState workState : this.workStates) {
            workState.switchToNextPhase();
            phase = workState.getCurrentPhase();
            LOG.info(phase.currentPhaseString());
            if (phase.rate < lowestRate) {
                lowestRate = phase.rate;
            }
        }

        long intervalNs = getInterval(lowestRate, phase.arrival);

        long nextInterval = start + intervalNs;
        int nextToAdd = 1;
        int rateFactor;

        boolean resetQueues = true;

        long delta = phase.time * 1000000000L;
        boolean lastEntry = false;

        // Initialize the Monitor
        if(this.intervalMonitor > 0 ) {
            new MonitorThread(this.intervalMonitor).start();
        }
        
        // Initialize the early abort monitor
        if (this.earlyAbortConfig != null) {
            new EarlyAbortThread(earlyAbortConfig).start();
        }

        // Main Loop
        while (true) {           
            // posting new work... and reseting the queue in case we have new
            // portion of the workload...

            for (WorkloadState workState : this.workStates) {
                if (workState.getCurrentPhase() != null) {
                    rateFactor = workState.getCurrentPhase().rate / lowestRate;
                } else {
                    rateFactor = 1;
                }
                workState.addToQueue(nextToAdd * rateFactor, resetQueues);
            }
            resetQueues = false;

            // Wait until the interval expires, which may be "don't wait"
            now = System.nanoTime();
            long diff = nextInterval - now;
            while (diff > 0) { // this can wake early: sleep multiple times to
                               // avoid that
                long ms = diff / 1000000;
                diff = diff % 1000000;
                try {
                    Thread.sleep(ms, (int) diff);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                now = System.nanoTime();
                diff = nextInterval - now;
            }
            assert diff <= 0;

            boolean phaseComplete = false;
            if (phase != null) {
                TraceReader tr = workConfs.get(0).getTraceReader();
                if (tr != null) {
                    // If a trace script is present, the phase complete iff the
                    // trace reader has no more 
                    for (WorkloadConfiguration workConf : workConfs) {
                        phaseComplete = false;
                        tr = workConf.getTraceReader();
                        assert workConf.getTraceReader() != null;
                        if (!workConf.getWorkloadState().getScriptPhaseComplete()) {
                            break;
                        }
                        phaseComplete = true;
                    }
                }
                else if (phase.isLatencyRun())
                    // Latency runs (serial run through each query) have their own
                    // state to mark completion
                    phaseComplete = testState.getState()
                                    == State.LATENCY_COMPLETE;
                else
                    phaseComplete = testState.getState() == State.MEASURE
                                    && (start + delta <= now);
            }

            // Go to next phase if this one is complete
            if (phaseComplete && !lastEntry) {
                // enters here after each phase of the test
                // reset the queues so that the new phase is not affected by the
                // queue of the previous one
                resetQueues = true;

                // Fetch a new Phase
                synchronized (testState) {
                    if (phase.isLatencyRun()) {
                        testState.ackLatencyComplete();
                    }
                    for (WorkloadState workState : workStates) {
                        synchronized (workState) {
                            workState.switchToNextPhase();
                            lowestRate = Integer.MAX_VALUE;
                            phase = workState.getCurrentPhase();
                            interruptWorkers();
                            if (phase == null && !lastEntry) {
                                // Last phase
                                lastEntry = true;
                                testState.startCoolDown();
                                measureEnd = now;
                                LOG.info("[Terminate] Waiting for all terminals to finish ..");
                            } else if (phase != null) {
                                phase.resetSerial();
                                LOG.info(phase.currentPhaseString());
                            if (phase.rate < lowestRate) {
                                lowestRate = phase.rate;
                            }
                        }
                    }
                    }
                    if (phase != null) {
                        // update frequency in which we check according to
                        // wakeup
                        // speed
                        // intervalNs = (long) (1000000000. / (double)
                        // lowestRate + 0.5);
                        delta += phase.time * 1000000000L;
                    }
                }
            }

            // Compute the next interval
            // and how many messages to deliver
            if (phase != null) {
                intervalNs = 0;
                nextToAdd = 0;
                do {
                    intervalNs += getInterval(lowestRate, phase.arrival);
                    nextToAdd++;
                } while ((-diff) > intervalNs && !lastEntry);
                nextInterval += intervalNs;
            }

            // Update the test state appropriately
            State state = testState.getState();
            if (state == State.WARMUP && now >= start) {
                synchronized(testState) {
                    if (phase != null && phase.isLatencyRun())
                        testState.startColdQuery();
                    else
                testState.startMeasure();
                    interruptWorkers();
                }
                start = now;
                LOG.info("[Measure] Warmup complete, starting measurements.");
                // measureEnd = measureStart + measureSeconds * 1000000000L;

                // For serial executions, we want to do every query exactly
                // once, so we need to restart in case some of the queries
                // began during the warmup phase.
                // If we're not doing serial executions, this function has no
                // effect and is thus safe to call regardless.
                phase.resetSerial();
            } else if (state == State.EXIT) {
                // All threads have noticed the done, meaning all measured
                // requests have definitely finished.
                // Time to quit.
                break;
            }
        } // WHILE

        try {
            int requests = finalizeWorkers(this.workerThreads);
            if (measureEnd < 0) {
                measureEnd = System.nanoTime();;
            }

            // Combine all the latencies together in the most disgusting way
            // possible: sorting!
            for (Worker w : workers) {
                for (LatencyRecord.Sample sample : w.getLatencyRecords()) {
                    samples.add(sample);
                }
            }
            Collections.sort(samples);

            // Compute stats on all the latencies
            int[] latencies = new int[samples.size()];
            for (int i = 0; i < samples.size(); ++i) {
                latencies[i] = samples.get(i).latencyUs;
            }
            DistributionStatistics stats = DistributionStatistics.computeStatistics(latencies);

            // Results results = new Results(measureEnd - start, requests, stats, samples);
            Results results = new Results(measureEnd - start, stats.getCount(), stats, samples);

            // Compute transaction histogram
            Set<TransactionType> txnTypes = new HashSet<TransactionType>();
            for (WorkloadConfiguration workConf : workConfs) {
                txnTypes.addAll(workConf.getTransTypes());
            }
            txnTypes.remove(TransactionType.INVALID);

            results.txnSuccess.putAll(txnTypes, 0);
            results.txnRetry.putAll(txnTypes, 0);
            results.txnAbort.putAll(txnTypes, 0);
            results.txnErrors.putAll(txnTypes, 0);

            for (Worker w : workers) {
                results.txnSuccess.putHistogram(w.getTransactionSuccessHistogram());
                results.txnRetry.putHistogram(w.getTransactionRetryHistogram());
                results.txnAbort.putHistogram(w.getTransactionAbortHistogram());
                results.txnErrors.putHistogram(w.getTransactionErrorHistogram());

                for (Entry<TransactionType, Histogram<String>> e : w.getTransactionAbortMessageHistogram().entrySet()) {
                    Histogram<String> h = results.txnAbortMessages.get(e.getKey());
                    if (h == null) {
                        h = new Histogram<String>(true);
                        results.txnAbortMessages.put(e.getKey(), h);
                    }
                    h.putHistogram(e.getValue());
                } // FOR
            } // FOR

            return (results);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private long getInterval(int lowestRate, Phase.Arrival arrival) {
        // TODO Auto-generated method stub
        if (arrival == Phase.Arrival.POISSON)
            return (long) ((-Math.log(1 - Math.random()) / lowestRate) * 1000000000.);
        else
            return (long) (1000000000. / (double) lowestRate + 0.5);
    }

    // public Results runPoissonMultiPhase() throws QueueLimitException,
    // IOException {
    // assert testState == null;
    // testState = new BenchmarkState(workers.size() + 1);
    // workStates = new ArrayList<WorkloadState>();
    //
    // for (WorkloadConfiguration workState : this.workConfs) {
    // workStates.add(workState.initializeState(testState));
    // }
    //
    // this.createWorkerThreads();
    // testState.blockForStart();
    //
    // long start = System.nanoTime();
    // long measureEnd = -1;
    //
    // //used to determine the longest sleep interval
    // int lowestRate = Integer.MAX_VALUE;
    //
    // Phase phase = null;
    //
    //
    // for (WorkloadState workState : this.workStates) {
    // workState.switchToNextPhase();
    // phase = workState.getCurrentPhase();
    // LOG.info(phase.currentPhaseString());
    // if (phase.rate < lowestRate) {
    // lowestRate = phase.rate;
    // }
    // }
    //
    // long intervalNs = getInterval(lowestRate);
    //
    // long nextInterval = start + intervalNs;
    // int nextToAdd = 1;
    // int rateFactor;
    //
    // boolean resetQueues = true;
    //
    // long delta = phase.time * 1000000000L;
    // boolean lastEntry = false;
    // int submitted=0;
    // while (true) {
    // // System.out.println(intervalNs);
    // // posting new work... and reseting the queue in case we have new
    // // portion of the workload...
    // submitted+=nextToAdd;
    // for (WorkloadState workState : this.workStates) {
    // if (workState.getCurrentPhase() != null) {
    // rateFactor = workState.getCurrentPhase().rate / lowestRate;
    // } else {
    // rateFactor = 1;
    // }
    // workState.addToQueue(nextToAdd * rateFactor, resetQueues);
    // }
    // resetQueues = false;
    //
    //
    // // Wait until the interval expires, which may be "don't wait"
    // long now = System.nanoTime();
    // long diff = nextInterval - now;
    // while (diff > 0) { // this can wake early: sleep multiple times to
    // // avoid that
    // long ms = diff / 1000000;
    // diff = diff % 1000000;
    // try {
    // Thread.sleep(ms, (int) diff);
    // } catch (InterruptedException e) {
    // throw new RuntimeException(e);
    // }
    // now = System.nanoTime();
    // diff = nextInterval - now;
    // }
    // assert diff <= 0;
    //
    // // End of Phase
    // if (start + delta < System.nanoTime() && !lastEntry) {
    // // enters here after each phase of the test
    // // reset the queues so that the new phase is not affected by the
    // // queue of the previous one
    // resetQueues = true;
    //
    // // Fetch a new Phase
    // synchronized (testState) {
    // for (WorkloadState workState : workStates) {
    // workState.switchToNextPhase();
    // lowestRate = Integer.MAX_VALUE;
    // phase = workState.getCurrentPhase();
    // if (phase == null) {
    // // Last phase
    // lastEntry = true;
    // break;
    // } else {
    // LOG.info(phase.currentPhaseString());
    // if (phase.rate < lowestRate) {
    // lowestRate = phase.rate;
    // }
    // }
    // }
    // if (phase != null) {
    // // update frequency in which we check according to wakeup
    // // speed
    // // intervalNs = (long) (1000000000. / (double) lowestRate + 0.5);
    // delta += phase.time * 1000000000L;
    // }
    // }
    // }
    //
    // // Compute the next interval and how many messages to deliver
    // if(phase != null)
    // {
    // intervalNs=0;
    // nextToAdd = 0;
    // do
    // {
    // intervalNs += getInterval(lowestRate);;
    // nextToAdd ++;
    // } while ( (-diff) > intervalNs && !lastEntry);
    // nextInterval += intervalNs;
    // assert nextToAdd > 0;
    // }
    // // Update the test state appropriately
    // State state = testState.getState();
    // if (state == State.WARMUP && now >= start) {
    // testState.startMeasure();
    // start = now;
    // // measureEnd = measureStart + measureSeconds * 1000000000L;
    // } else if (state == State.MEASURE && lastEntry && now >= start + delta) {
    // System.out.println("### ToTal: "+ submitted);
    // testState.startCoolDown();
    // LOG.info("[Terminate] Waiting for all terminals to finish ..");
    // measureEnd = now;
    // } else if (state == State.EXIT) {
    // // All threads have noticed the done, meaning all measured
    // // requests have definitely finished.
    // // Time to quit.
    // break;
    // }
    // }
    //
    // try {
    // int requests = finalizeWorkers(this.workerThreads);
    //
    // // Combine all the latencies together in the most disgusting way
    // // possible: sorting!
    // for (Worker w : workers) {
    // for (LatencyRecord.Sample sample : w.getLatencyRecords()) {
    // samples.add(sample);
    // }
    // }
    // Collections.sort(samples);
    //
    // // Compute stats on all the latencies
    // int[] latencies = new int[samples.size()];
    // for (int i = 0; i < samples.size(); ++i) {
    // latencies[i] = samples.get(i).latencyUs;
    // }
    // DistributionStatistics stats =
    // DistributionStatistics.computeStatistics(latencies);
    //
    // Results results = new Results(measureEnd - start, requests, stats,
    // samples);
    //
    // // Compute transaction histogram
    // Set<TransactionType> txnTypes = new HashSet<TransactionType>();
    // for (WorkloadConfiguration workConf : workConfs) {
    // txnTypes.addAll(workConf.getTransTypes());
    // }
    // txnTypes.remove(TransactionType.INVALID);
    //
    // results.txnSuccess.putAll(txnTypes, 0);
    // results.txnRetry.putAll(txnTypes, 0);
    // results.txnAbort.putAll(txnTypes, 0);
    // results.txnErrors.putAll(txnTypes, 0);
    //
    //
    // for (Worker w : workers) {
    // results.txnSuccess.putHistogram(w.getTransactionSuccessHistogram());
    // results.txnRetry.putHistogram(w.getTransactionRetryHistogram());
    // results.txnAbort.putHistogram(w.getTransactionAbortHistogram());
    // results.txnErrors.putHistogram(w.getTransactionErrorHistogram());
    //
    // for (Entry<TransactionType, Histogram<String>> e :
    // w.getTransactionAbortMessageHistogram().entrySet()) {
    // Histogram<String> h = results.txnAbortMessages.get(e.getKey());
    // if (h == null) {
    // h = new Histogram<String>(true);
    // results.txnAbortMessages.put(e.getKey(), h);
    // }
    // h.putHistogram(e.getValue());
    // } // FOR
    // } // FOR
    //
    // return (results);
    // } catch (InterruptedException e) {
    // throw new RuntimeException(e);
    // }
    // }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        // Something bad happened! Tell all of our workers that the party is
        // over!
        // synchronized (this) {
        // if (this.calledTearDown == false) {
        // for (Worker w : this.workers) {
        // w.tearDown(true);
        // }
        // }
        // this.calledTearDown = true;
        // } // SYNCH
        //

        // HERE WE HANDLE THE CASE IN WHICH ONE OF OUR WOKERTHREADS DIED
        e.printStackTrace();
        System.exit(-1);

        /*
         * Alternatively, we could keep an HashMap<Thread,Worker> storing the
         * runnable for each thread, so that we can get the latency numbers from
         * a thread that died, and either continue or at least report current
         * status. (Remember to remove this thread from the list of threads to
         * wait for)
         */

    }

}
