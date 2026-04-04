package com.ics.dispatcher.grpc.client;

import com.ics.smallmodel.grpc.proto.*;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

/**
 * ============================================================================
 * 小模型服务 gRPC 客户端
 * ============================================================================
 * 调度层通过此客户端调用小模型服务层的训练接口
 *
 * 主要调用:
 * - SmallModelTrainingService.StartTraining - 触发增量训练
 *
 * 连接配置: grpc.client.small-model-service (application.yml)
 */
@Slf4j
@Component
public class SmallModelGrpcClient {

    /**
     * 小模型训练服务 Stub
     * "small-model-service" 对应 application.yml 中的 gRPC 客户端配置
     */
    @GrpcClient("small-model-service")
    private SmallModelTrainingServiceGrpc.SmallModelTrainingServiceBlockingStub trainingStub;

    /**
     * 触发小模型增量训练 (重构: 使用 datasetUri 传递 OSS 文件路径)
     *
     * 在语料入库完成后，调度模块调用此方法触发小模型进行增量训练
     * 训练端通过 datasetUri (oss://...) 下载语料数据
     *
     * @param trainingTaskId 训练任务ID（幂等键）
     * @param sourceTaskId   来源调度任务ID
     * @param datasetUri     数据集 URI (如 oss://corpus/raw/batch_xxx.jsonl)
     * @param rowCount       语料行数
     * @return 是否成功触发
     */
    public boolean startTraining(String trainingTaskId,
                                 String sourceTaskId,
                                 String datasetUri,
                                 int rowCount) {

        log.info("[gRPC客户端→小模型] 触发增量训练, trainingTaskId={}, sourceTaskId={}, datasetUri={}, rows={}",
                trainingTaskId, sourceTaskId, datasetUri, rowCount);

        try {
            TrainingRequest request = TrainingRequest.newBuilder()
                    .setTrainingTaskId(trainingTaskId)
                    .setSourceTaskId(sourceTaskId)
                    .setDatasetUri(datasetUri)
                    .setTrainingType(TrainingType.INCREMENTAL)
                    .setConfig(TrainingConfig.newBuilder()
                            .setEpochs(3)
                            .setLearningRate(0.001f)
                            .setBatchSize(16)
                            .build())
                    .build();

            TrainingResponse response = trainingStub.startTraining(request);

            if (response.getCode() == 0) {
                log.info("[gRPC客户端→小模型] 训练请求已接收, trainingTaskId={}", trainingTaskId);
                return true;
            } else {
                log.warn("[gRPC客户端→小模型] 训练请求被拒绝, trainingTaskId={}, code={}, msg={}",
                        trainingTaskId, response.getCode(), response.getMessage());
                return false;
            }

        } catch (StatusRuntimeException e) {
            log.error("[gRPC客户端→小模型] gRPC调用异常, trainingTaskId={}, status={}",
                    trainingTaskId, e.getStatus(), e);
            throw new RuntimeException("调用小模型训练服务失败: " + e.getStatus().getDescription(), e);

        } catch (Exception e) {
            log.error("[gRPC客户端→小模型] 调用异常, trainingTaskId={}, error={}",
                    trainingTaskId, e.getMessage(), e);
            throw new RuntimeException("调用小模型训练服务失败: " + e.getMessage(), e);
        }
    }

    /**
     * 查询训练状态
     *
     * @param trainingTaskId 训练任务ID
     * @return 训练状态描述
     */
    public String queryTrainingStatus(String trainingTaskId) {
        log.info("[gRPC客户端→小模型] 查询训练状态, trainingTaskId={}", trainingTaskId);

        try {
            TrainingStatusRequest request = TrainingStatusRequest.newBuilder()
                    .setTrainingTaskId(trainingTaskId)
                    .build();

            TrainingStatusResponse response = trainingStub.queryTrainingStatus(request);
            log.info("[gRPC客户端→小模型] 训练状态: {}, progress={}, msg={}",
                    response.getStatus(), response.getProgress(), response.getMessage());

            return response.getStatus();

        } catch (Exception e) {
            log.error("[gRPC客户端→小模型] 查询训练状态异常, trainingTaskId={}", trainingTaskId, e);
            return "unknown";
        }
    }
}
