package org.pytorch.serve.wlm;

import com.google.gson.JsonObject;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.pytorch.serve.archive.model.ModelArchive;
import org.pytorch.serve.archive.model.ModelConfig;
import org.pytorch.serve.archive.utils.ArchiveUtils;
import org.pytorch.serve.job.Job;
import org.pytorch.serve.util.ConfigManager;
import org.pytorch.serve.util.messages.WorkerCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Model {

    public static final String DEFAULT_DATA_QUEUE = "DATA_QUEUE";
    public static final String MIN_WORKERS = "minWorkers";
    public static final String MAX_WORKERS = "maxWorkers";
    public static final String BATCH_SIZE = "batchSize";
    public static final String MAX_BATCH_DELAY = "maxBatchDelay";
    public static final String RESPONSE_TIMEOUT = "responseTimeout";
    public static final String PARALLEL_LEVEL = "parallelLevel";
    public static final String DEFAULT_VERSION = "defaultVersion";
    public static final String MAR_NAME = "marName";

    private static final Logger logger = LoggerFactory.getLogger(Model.class);

    private ModelArchive modelArchive;
    private int minWorkers;
    private int maxWorkers;
    private int batchSize;
    private int maxBatchDelay;
    private int parallelLevel = 1;
    private long maxRetryTimeoutInMill = 5 * 60 * 1000;
    private long clientTimeoutInMills;
    private ModelConfig.ParallelType parallelType = ModelConfig.ParallelType.NONE;
    private ModelConfig.DeviceType deviceType =
            ConfigManager.getInstance().getNumberOfGpu() > 0
                    ? ModelConfig.DeviceType.GPU
                    : ModelConfig.DeviceType.CPU;
    private List<Integer> deviceIds;
    private int numCores;
    private ReentrantLock lock;
    private int responseTimeout;
    private ModelVersionName modelVersionName;
    private AtomicInteger gpuCounter = new AtomicInteger(0);
    private boolean hasCfgDeviceIds;
    private boolean isWorkflowModel;

    // Total number of subsequent inference request failures
    private AtomicInteger failedInfReqs;

    // Per worker thread job queue. This separates out the control queue from data queue
    private ConcurrentMap<String, LinkedBlockingDeque<Job>> jobsDb;

    private boolean useJobTicket;
    private AtomicInteger numJobTickets;

    public Model(ModelArchive modelArchive, int queueSize) {
        this.modelArchive = modelArchive;
        if (modelArchive != null && modelArchive.getModelConfig() != null) {
            if (modelArchive.getModelConfig().getParallelLevel() > 1
                    && modelArchive.getModelConfig().getParallelType()
                            != ModelConfig.ParallelType.NONE) {
                parallelLevel = modelArchive.getModelConfig().getParallelLevel();
                parallelType = modelArchive.getModelConfig().getParallelType();
            }
            if (modelArchive.getModelConfig().getDeviceType() != ModelConfig.DeviceType.NONE) {
                deviceType =
                        (modelArchive.getModelConfig().getDeviceType() == ModelConfig.DeviceType.GPU
                                        && ConfigManager.getInstance().getNumberOfGpu() > 0)
                                ? ModelConfig.DeviceType.GPU
                                : ModelConfig.DeviceType.CPU;
            }

            deviceIds = modelArchive.getModelConfig().getDeviceIds();
            if (deviceIds != null && deviceIds.size() > 0) {
                hasCfgDeviceIds = true;
                for (Integer deviceId : deviceIds) {
                    if (deviceId < 0 || deviceId >= ConfigManager.getInstance().getNumberOfGpu()) {
                        logger.warn("Invalid deviceId:{}, ignore deviceIds list", deviceId);
                        deviceIds = null;
                        hasCfgDeviceIds = false;
                        break;
                    }
                }
            }
            maxRetryTimeoutInMill = modelArchive.getModelConfig().getMaxRetryTimeoutInSec() * 1000;
            clientTimeoutInMills = modelArchive.getModelConfig().getClientTimeoutInMills();
            if (modelArchive.getModelConfig().getJobQueueSize() > 0) {
                // overwrite the queueSize defined on config.property
                queueSize = modelArchive.getModelConfig().getJobQueueSize();
            }
            useJobTicket = modelArchive.getModelConfig().isUseJobTicket();
        } else {
            batchSize = 1;
            maxBatchDelay = 100;
        }

        if (ConfigManager.getInstance().getNumberOfGpu() > 0
                && deviceType != ModelConfig.DeviceType.CPU) {
            numCores =
                    hasCfgDeviceIds
                            ? deviceIds.size()
                            : ConfigManager.getInstance().getNumberOfGpu();
        }

        jobsDb = new ConcurrentHashMap<>();
        // Always have a queue for data
        jobsDb.putIfAbsent(DEFAULT_DATA_QUEUE, new LinkedBlockingDeque<>(queueSize));
        failedInfReqs = new AtomicInteger(0);
        numJobTickets = new AtomicInteger(0);
        lock = new ReentrantLock();
        modelVersionName =
                new ModelVersionName(
                        this.modelArchive.getModelName(), this.modelArchive.getModelVersion());
    }

    public JsonObject getModelState(boolean isDefaultVersion) {

        JsonObject modelInfo = new JsonObject();
        modelInfo.addProperty(DEFAULT_VERSION, isDefaultVersion);
        modelInfo.addProperty(MAR_NAME, ArchiveUtils.getFilenameFromUrl(getModelUrl()));
        modelInfo.addProperty(MIN_WORKERS, getMinWorkers());
        modelInfo.addProperty(MAX_WORKERS, getMaxWorkers());
        modelInfo.addProperty(BATCH_SIZE, getBatchSize());
        modelInfo.addProperty(MAX_BATCH_DELAY, getMaxBatchDelay());
        modelInfo.addProperty(RESPONSE_TIMEOUT, getResponseTimeout());
        if (parallelLevel > 1) {
            modelInfo.addProperty(PARALLEL_LEVEL, parallelLevel);
        }

        return modelInfo;
    }

    public void setModelState(JsonObject modelInfo) {
        minWorkers = modelInfo.get(MIN_WORKERS).getAsInt();
        maxWorkers = modelInfo.get(MAX_WORKERS).getAsInt();
        maxBatchDelay = modelInfo.get(MAX_BATCH_DELAY).getAsInt();
        responseTimeout = modelInfo.get(RESPONSE_TIMEOUT).getAsInt();
        batchSize = modelInfo.get(BATCH_SIZE).getAsInt();
        if (modelInfo.get(PARALLEL_LEVEL) != null) {
            parallelLevel = modelInfo.get(PARALLEL_LEVEL).getAsInt();
        }
    }

    public String getModelName() {
        return modelArchive.getModelName();
    }

    public ModelVersionName getModelVersionName() {
        return modelVersionName;
    }

    public String getVersion() {
        return modelArchive.getModelVersion();
    }

    public File getModelDir() {
        return modelArchive.getModelDir();
    }

    public String getModelUrl() {
        return modelArchive.getUrl();
    }

    public ModelArchive getModelArchive() {
        return modelArchive;
    }

    public int getMinWorkers() {
        return minWorkers;
    }

    public void setMinWorkers(int minWorkers) {
        this.minWorkers = minWorkers;
    }

    public int getMaxWorkers() {
        return maxWorkers;
    }

    public void setMaxWorkers(int maxWorkers) {
        this.maxWorkers = maxWorkers;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxBatchDelay() {
        return maxBatchDelay;
    }

    public void setMaxBatchDelay(int maxBatchDelay) {
        this.maxBatchDelay = maxBatchDelay;
    }

    public boolean isWorkflowModel() {
        return isWorkflowModel;
    }

    public void setWorkflowModel(boolean workflowModel) {
        isWorkflowModel = workflowModel;
    }

    public void addJob(String threadId, Job job) {
        LinkedBlockingDeque<Job> blockingDeque = jobsDb.get(threadId);
        if (blockingDeque == null) {
            blockingDeque = new LinkedBlockingDeque<>();
            jobsDb.put(threadId, blockingDeque);
        }
        blockingDeque.offer(job);
    }

    public void removeJobQueue(String threadId) {
        if (!threadId.equals(DEFAULT_DATA_QUEUE)) {
            jobsDb.remove(threadId);
        }
    }

    public boolean addJob(Job job) {
        if (isUseJobTicket() && !getJobTickets()) {
            logger.info("There are no job tickets");
            return false;
        }
        return jobsDb.get(DEFAULT_DATA_QUEUE).offer(job);
    }

    public void addFirst(Job job) {
        jobsDb.get(DEFAULT_DATA_QUEUE).addFirst(job);
    }

    public void pollBatch(String threadId, long waitTime, Map<String, Job> jobsRepo)
            throws InterruptedException {
        if (jobsRepo == null || threadId == null || threadId.isEmpty()) {
            throw new IllegalArgumentException("Invalid input given provided");
        }

        if (!jobsRepo.isEmpty()) {
            throw new IllegalArgumentException(
                    "The jobs repo provided contains stale jobs. Clear them!!");
        }

        LinkedBlockingDeque<Job> jobsQueue = jobsDb.get(threadId);
        if (jobsQueue != null && !jobsQueue.isEmpty()) {
            Job j = jobsQueue.poll(waitTime, TimeUnit.MILLISECONDS);
            if (j != null) {
                jobsRepo.put(j.getJobId(), j);
                return;
            }
        }

        try {
            if (isUseJobTicket()) {
                incNumJobTickets();
            }
            lock.lockInterruptibly();
            long maxDelay = maxBatchDelay;
            jobsQueue = jobsDb.get(DEFAULT_DATA_QUEUE);

            Job j = jobsQueue.poll(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
            logger.trace("get first job: {}", Objects.requireNonNull(j).getJobId());

            jobsRepo.put(j.getJobId(), j);
            // batch size always is 1 for describe request job and stream prediction request job
            if (j.getCmd() == WorkerCommands.DESCRIBE
                    || j.getCmd() == WorkerCommands.STREAMPREDICT) {
                return;
            }
            long begin = System.currentTimeMillis();
            for (int i = 0; i < batchSize - 1; ++i) {
                j = jobsQueue.poll(maxDelay, TimeUnit.MILLISECONDS);
                if (j == null) {
                    break;
                }
                long end = System.currentTimeMillis();
                // job batch size always is 1 when request is
                // describe or stream prediction
                if (j.getCmd() == WorkerCommands.DESCRIBE
                        || j.getCmd() == WorkerCommands.STREAMPREDICT) {
                    // Add the job back into the jobsQueue
                    jobsQueue.addFirst(j);
                    break;
                }
                maxDelay -= end - begin;
                begin = end;
                if (j.getPayload().getClientExpireTS() > System.currentTimeMillis()) {
                    jobsRepo.put(j.getJobId(), j);
                } else {
                    logger.warn(
                            "Drop inference request {} due to client timeout",
                            j.getPayload().getRequestId());
                }
                if (maxDelay <= 0) {
                    break;
                }
            }
            logger.trace("sending jobs, size: {}", jobsRepo.size());
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public int incrFailedInfReqs() {
        return failedInfReqs.incrementAndGet();
    }

    public void resetFailedInfReqs() {
        failedInfReqs.set(0);
    }

    public int getResponseTimeout() {
        return ConfigManager.getInstance().isDebug() ? Integer.MAX_VALUE : responseTimeout;
    }

    public void setResponseTimeout(int responseTimeout) {
        this.responseTimeout = responseTimeout;
    }

    public List<Integer> getDeviceIds() {
        return this.deviceIds;
    }

    public void setDeviceIds(List<Integer> deviceIds) {
        Collections.copy(this.deviceIds, deviceIds);
    }

    public int getParallelLevel() {
        return this.parallelLevel;
    }

    public ModelConfig.ParallelType getParallelType() {
        return this.parallelType;
    }

    public ModelConfig.DeviceType getDeviceType() {
        return this.deviceType;
    }

    public int getNumCores() {
        return this.numCores;
    }

    public AtomicInteger getGpuCounter() {
        return gpuCounter;
    }

    public boolean isHasCfgDeviceIds() {
        return hasCfgDeviceIds;
    }

    public long getMaxRetryTimeoutInMill() {
        return maxRetryTimeoutInMill;
    }

    public void setMaxRetryTimeoutInMill(long maxRetryTimeoutInMill) {
        this.maxRetryTimeoutInMill = maxRetryTimeoutInMill;
    }

    public long getClientTimeoutInMills() {
        return clientTimeoutInMills;
    }

    public void setClientTimeoutInMills(long clientTimeoutInMills) {
        this.clientTimeoutInMills = clientTimeoutInMills;
    }

    public boolean isUseJobTicket() {
        return useJobTicket;
    }

    public int incNumJobTickets() {
        return this.numJobTickets.incrementAndGet();
    }

    public int decNumJobTickets() {
        return this.numJobTickets.decrementAndGet();
    }

    public synchronized boolean getJobTickets() {
        if (this.numJobTickets.get() == 0) {
            return false;
        }

        this.numJobTickets.decrementAndGet();
        return true;
    }
}
