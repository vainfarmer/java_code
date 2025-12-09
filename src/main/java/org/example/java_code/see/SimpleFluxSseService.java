package org.example.java_code.see;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * SSE服务 - 创建Flux并推送数据给前端
 * <p>
 * 流程： 1. 创建定时的Flux流（每隔一段时间发送数据） 2. 对Flux中的每个数据进行处理（添加标记） 3. 转换为JSON字符串 4.
 * 通过SseEmitter实时推送给前端 5.
 * 前端通过EventSource接收并显示
 */
@Slf4j
@Service
public class SimpleFluxSseService {

  private static final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * 保存可取消的 SSE 流上下文，key 为 streamId。
   */
  private final Map<String, CancelableStreamContext> cancelableStreamsMap = new ConcurrentHashMap<>();

  /**
   * 创建简单的Flux SSE流
   * <p>
   * 类似于 CommonChatOpenApiController.testByFeedBack() 的逻辑： 1. 创建SseEmitter 2.
   * 异步执行流处理 3.
   * 返回SseEmitter给前端 4. 前端创建EventSource连接接收数据
   *
   * @param emitter SSE发送器
   */
  public void createSimpleFluxStream(SseEmitter emitter) throws IOException {
    // 在独立的线程中处理Flux流（不阻塞HTTP响应）
    // 这样前端可以立即收到HTTP 200，然后建立SSE连接
    long startTime = System.currentTimeMillis();
    Flux<Integer> flux = Flux.range(1, 10); // 生成1到10的数字
    // Flux.range(1, 10) // 生成1到10的数字
    // 每个数字延迟500ms发送（模拟处理时间）
    flux.delayElements(Duration.ofMillis(500))
        // 对每个数据进行处理（添加标记和标签）
        .map(number -> processDataWithLabel(number))
        // 转换为JSON字符串
        .map(data -> {
          try {
            return objectMapper.writeValueAsString(data);
          } catch (Exception e) {
            log.error("JSON转换失败", e);
            return null;
          }
        })
        // 发送给前端
        .subscribe(
            // onNext: 成功处理每个数据
            json -> {
              try {
                long elapsed = System.currentTimeMillis() - startTime;
                log.info("📤 [{}ms] 发送SSE数据: {}", elapsed, json);
                emitter.send(SseEmitter.event()
                    .id(System.currentTimeMillis() + "")
                    .name("simpleFluxEvent")
                    .data(json)
                    // 注意：移除 .reconnectTime(1000) 以防止浏览器自动重新连接
                    // 如果客户端断开连接，不会自动重新连接
                    .build());
              } catch (IOException e) {
                log.error("SSE发送失败", e);
                emitter.completeWithError(e);
              }
            },
            // onError: 处理异常
            error -> {
              log.error("❌ Flux流异常: {}", error.getMessage());
              try {
                emitter.completeWithError(error);
              } catch (Exception e) {
                log.error("错误回调异常", e);
              }
            },
            // onComplete: 流完成
            () -> {
              long totalTime = System.currentTimeMillis() - startTime;
              log.info("✅ Flux流完成（总耗时: {}ms），关闭SSE连接", totalTime);
              try {
                emitter.complete();
              } catch (Exception e) {
                log.error("关闭连接失败", e);
              }
            });
  }

  /**
   * 创建一个可取消的长时间 SSE 流。
   *
   * @param streamId 流标识
   * @param emitter  SSE 发送器
   * @throws IOException when stream init fails
   */
  public void createCancelableFluxStream(String streamId, SseEmitter emitter) throws IOException {
    CancelableStreamContext context = new CancelableStreamContext(streamId, emitter);
    cancelableStreamsMap.put(streamId, context);

    sendCancelableEvent(emitter, streamId, -1, "CONTROL", "control",
        "流已建立，streamId=" + streamId);

    Disposable disposable = Flux.interval(Duration.ofSeconds(1))
        .subscribe(sequence -> {
          try {
            sendCancelableEvent(emitter, streamId, sequence.intValue(), "RUNNING", "running",
                "后台持续发送数据 #" + sequence);
          } catch (IOException e) {
            log.error("❌ [{}] 可取消流发送失败: {}", streamId, e.getMessage());
            cancelCancelableStream(streamId, "error", "发送异常，自动终止");
          }
        });
    context.setDisposable(disposable);

    emitter.onCompletion(() -> cleanupCancelableStream(streamId, "complete"));
    emitter.onTimeout(() -> cancelCancelableStream(streamId, "timeout", "连接超时"));
    emitter.onError(throwable -> cancelCancelableStream(streamId, "error", "发生错误: " + throwable.getMessage()));
  }

  /**
   * 取消并清理可取消流。
   *
   * @param streamId 流标识
   * @param status   状态
   * @param message  消息
   * @return 是否找到并取消成功
   */
  public boolean cancelCancelableStream(String streamId, String status, String message) {
    CancelableStreamContext context = cancelableStreamsMap.get(streamId);
    if (context == null || context.closed.get()) {
      return false;
    }

    context.closed.set(true);
    Disposable disposable = context.disposable;
    if (disposable != null && !disposable.isDisposed()) {
      disposable.dispose();
    }

    try {
      sendCancelableEvent(context.emitter, streamId, -1, "CANCELLED", status, message);
    } catch (IOException e) {
      log.warn("发送取消事件失败: {}", e.getMessage());
    }

    try {
      context.emitter.complete();
    } catch (Exception e) {
      log.warn("关闭 emitter 失败: {}", e.getMessage());
    } finally {
      cancelableStreamsMap.remove(streamId);
    }
    return true;
  }

  /**
   * 对外暴露的取消接口，默认提示消息。
   */
  public boolean cancelCancelableStreamManual(String streamId) {
    return cancelCancelableStream(streamId, "cancelled", "客户端主动取消");
  }

  private void cleanupCancelableStream(String streamId, String reason) {
    CancelableStreamContext context = cancelableStreamsMap.get(streamId);
    if (context != null && context.closed.compareAndSet(false, true)) {
      Disposable disposable = context.disposable;
      if (disposable != null && !disposable.isDisposed()) {
        disposable.dispose();
      }
      cancelableStreamsMap.remove(streamId);
      log.info("🧹 [{}] 清理可取消流 (原因: {})", streamId, reason);
    }
  }

  private void sendCancelableEvent(SseEmitter emitter,
      String streamId,
      int number,
      String label,
      String status,
      String message) throws IOException {
    emitter.send(SseEmitter.event()
        .name("cancelableEvent")
        .id(streamId + "-" + System.currentTimeMillis())
        .data(Map.of(
            "streamId", streamId,
            "dataNumber", number,
            "label", label,
            "status", status,
            "message", message,
            "timestamp", System.currentTimeMillis())));
  }

  private static class CancelableStreamContext {
    private final SseEmitter emitter;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private Disposable disposable;

    private CancelableStreamContext(String streamId, SseEmitter emitter) {
      this.emitter = emitter;
    }

    private void setDisposable(Disposable disposable) {
      this.disposable = disposable;
    }
  }

  /**
   * 处理数据并添加标记
   * <p>
   * 类似于 processCommonChatEventWithTracker() 的逻辑： 1. 接收流中的数据（简单数字） 2.
   * 进行加工处理（添加标记、标签、状态等） 3.
   * 返回处理后的数据对象
   *
   * @param number 数字
   * @return 处理后的数据对象
   */
  private SimpleFluxSseData processDataWithLabel(Integer number) {
    String label;
    String status;
    String message;

    // 根据数字进行不同的处理
    switch (number) {
      case 1:
      case 2:
      case 3:
        label = "INITIALIZATION";
        status = "processing";
        message = "初始化阶段...";
        break;
      case 4:
      case 5:
      case 6:
        label = "PROCESSING";
        status = "processing";
        message = "处理数据中...";
        break;
      case 7:
      case 8:
      case 9:
        label = "VERIFICATION";
        status = "processing";
        message = "验证结果中...";
        break;
      case 10:
        label = "COMPLETED";
        status = "completed";
        message = "处理完成！";
        break;
      default:
        label = "UNKNOWN";
        status = "unknown";
        message = "未知状态";
    }

    return SimpleFluxSseData.builder()
        .dataNumber(number)
        .label(label)
        .status(status)
        .timestamp(System.currentTimeMillis())
        .message(message)
        .build();
  }

  /**
   * 创建更复杂的Flux流示例（多个阶段）
   *
   * @param emitter SSE发送器
   */
  public void createMultiStageFluxStream(SseEmitter emitter) {
    // 第一个阶段：1-3
    Flux<Integer> stage1 = Flux.range(1, 3)
        .delayElements(Duration.ofMillis(300))
        .doOnNext(n -> log.info("Stage 1: {}", n));

    // 第二个阶段：4-6
    Flux<Integer> stage2 = Flux.range(4, 3)
        .delayElements(Duration.ofMillis(300))
        .doOnNext(n -> log.info("Stage 2: {}", n));

    // 第三个阶段：7-9
    Flux<Integer> stage3 = Flux.range(7, 3)
        .delayElements(Duration.ofMillis(300))
        .doOnNext(n -> log.info("Stage 3: {}", n));

    // 合并多个阶段
    Flux.concat(stage1, stage2, stage3)
        .map(this::processDataWithLabel)
        .map(data -> {
          try {
            return objectMapper.writeValueAsString(data);
          } catch (Exception e) {
            return null;
          }
        })
        .subscribe(
            json -> {
              try {
                emitter.send(SseEmitter.event()
                    .id(System.currentTimeMillis() + "")
                    .name("multiStageEvent")
                    .data(json)
                    .build());
              } catch (IOException e) {
                log.error("SSE发送失败", e);
                emitter.completeWithError(e);
              }
            },
            error -> {
              log.error("Flux异常", error);
              emitter.completeWithError(error);
            },
            () -> {
              log.info("多阶段流完成");
              emitter.complete();
            });
  }
}
