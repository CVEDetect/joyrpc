package io.joyrpc.util;

/*-
 * #%L
 * joyrpc
 * %%
 * Copyright (C) 2019 joyrpc.io
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import io.joyrpc.event.Event;
import io.joyrpc.event.EventHandler;
import io.joyrpc.exception.InitializationException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static io.joyrpc.util.Status.*;

/**
 * 状态机
 */
public class StateMachine<T extends StateMachine.Controller> {

    protected static final AtomicReferenceFieldUpdater<StateMachine, Status> STATE_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(StateMachine.class, Status.class, "status");

    /**
     * 控制器提供者
     */
    protected Supplier<T> supplier;
    /**
     * 事件处理器
     */
    protected EventHandler<StateEvent> handler;

    /**
     * 打开的结果
     */
    protected StateFuture<Void> stateFuture = new StateFuture<>(null, null);

    /**
     * 状态
     */
    protected volatile Status status = Status.CLOSED;
    /**
     * 控制器
     */
    protected T controller;

    public StateMachine(final Supplier<T> supplier) {
        this.supplier = supplier;
    }

    public StateMachine(final Supplier<T> supplier, final EventHandler<StateEvent> handler) {
        this.supplier = supplier;
        this.handler = handler;
    }

    /**
     * 大开
     *
     * @return Future
     */
    public CompletableFuture<Void> open() {
        return open(null, handler);
    }

    /**
     * 大开
     *
     * @param runnable 执行块
     * @return Future
     */
    public CompletableFuture<Void> open(final Runnable runnable) {
        return open(runnable, handler);
    }

    /**
     * 大开
     *
     * @param runnable 执行块
     * @param handler  事件处理器
     * @return Future
     */
    public CompletableFuture<Void> open(final Runnable runnable, final EventHandler<StateEvent> handler) {
        if (STATE_UPDATER.compareAndSet(this, Status.CLOSED, Status.OPENING)) {
            //这个时候才创建Future，存在并发风险，并发调用getOpenFuture为空
            final CompletableFuture<Void> future = stateFuture.newOpenFuture();
            //把通知事件放在newOpenFuture后，可以减少并发问题
            publish(EventType.START_OPEN, null, handler);
            final T cc = supplier.get();
            controller = cc;
            //在赋值controller之后执行
            if (runnable != null) {
                runnable.run();
            }
            cc.open().whenComplete((v, e) -> {
                if (stateFuture.getOpenFuture() != future
                        || e == null && !STATE_UPDATER.compareAndSet(this, Status.OPENING, Status.OPENED)) {
                    //先关闭
                    cc.close(false);
                    InitializationException ex = new InitializationException("state is illegal.");
                    publish(EventType.FAIL_OPEN_ILLEGAL_STATE, ex, handler);
                    future.completeExceptionally(ex);
                } else if (e != null) {
                    //先关闭，防止事件触发判断状态还是OPENED
                    close(false);
                    publish(EventType.FAIL_OPEN, e, handler);
                    future.completeExceptionally(e);
                } else {
                    publish(EventType.START_OPEN, null, handler);
                    future.complete(null);
                }
            });
            return future;
        } else {
            CompletableFuture<Void> result = null;
            while (result == null) {
                switch (status) {
                    case OPENING:
                        result = stateFuture.getOpenFuture();
                        if (result == null) {
                            //并发问题，这个时候可能还没有创建好Future，等待一下
                            LockSupport.parkNanos(1);
                        }
                        break;
                    case OPENED:
                        //可重入，没有并发调用
                        return stateFuture.getOpenFuture();
                    default:
                        //其它状态不应该并发执行
                        return Futures.completeExceptionally(new InitializationException("state is illegal."));
                }
            }
            return result;
        }
    }

    /**
     * 关闭
     *
     * @param gracefully 优雅关闭标识
     * @return Future
     */
    public CompletableFuture<Void> close(final boolean gracefully) {
        return close(gracefully, null, handler);
    }

    /**
     * 关闭
     *
     * @param gracefully 优雅关闭标识
     * @param runnable   额外关闭操作
     * @return Future
     */
    public CompletableFuture<Void> close(final boolean gracefully, final Runnable runnable) {
        return close(gracefully, runnable, handler);
    }

    /**
     * 关闭
     *
     * @param gracefully 优雅关闭标识
     * @param runnable   额外关闭操作
     * @param handler    事件处理器
     * @return Future
     */
    public CompletableFuture<Void> close(final boolean gracefully, final Runnable runnable, final EventHandler<StateEvent> handler) {
        if (STATE_UPDATER.compareAndSet(this, OPENING, CLOSING)) {
            CompletableFuture<Void> future = stateFuture.newCloseFuture();
            publish(EventType.START_CLOSE, handler);
            controller.broken();
            stateFuture.getOpenFuture().whenComplete((v, e) -> {
                if (runnable != null) {
                    runnable.run();
                }
                //openFuture完成后会自动关闭控制器
                publish(EventType.SUCCESS_CLOSE, handler);
                status = CLOSED;
                controller = null;
                future.complete(null);
            });
            return future;
        } else if (STATE_UPDATER.compareAndSet(this, OPENED, CLOSING)) {
            //状态从打开到关闭中，该状态只能变更为CLOSED
            publish(EventType.START_CLOSE, handler);
            CompletableFuture<Void> future = stateFuture.newCloseFuture();
            if (runnable != null) {
                runnable.run();
            }
            controller.close(gracefully).whenComplete((o, s) -> {
                status = CLOSED;
                controller = null;
                publish(EventType.SUCCESS_CLOSE, handler);
                future.complete(null);
            });
            return future;
        } else {
            CompletableFuture<Void> result = null;
            while (result == null) {
                switch (status) {
                    case CLOSING:
                        result = stateFuture.getCloseFuture();
                        if (result == null) {
                            //并发问题，这个时候可能还没有创建好Future，等待一下
                            LockSupport.parkNanos(1);
                        }
                        break;
                    case CLOSED:
                        return stateFuture.getCloseFuture();
                    default:
                        return Futures.completeExceptionally(new IllegalStateException("Status is illegal."));
                }
            }
            return result;
        }
    }

    /**
     * 在指定状态下执行
     *
     * @param predicate 条件
     * @param consumer  消费者
     * @return 状态是否匹配
     */
    public boolean when(final Predicate<Status> predicate, final Consumer<T> consumer) {
        if (consumer != null && (predicate == null || predicate.test(status))) {
            T c = controller;
            if (c != null) {
                consumer.accept(c);
                return true;
            }
        }
        return false;
    }

    /**
     * 在打开状态下执行
     *
     * @param consumer 消费者
     * @return 没有关闭标识
     */
    public boolean whenOpen(final Consumer<T> consumer) {
        return when(Status::isOpen, consumer);
    }

    /**
     * 在打开状态下执行
     *
     * @param consumer 消费者
     * @return 打开标识
     */
    public boolean whenOpened(final Consumer<T> consumer) {
        return when(s -> s == OPENED, consumer);
    }

    public T getController() {
        return controller;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isOpen(final Controller controller) {
        return controller == this.controller && status.isOpen();
    }

    /**
     * 发布事件
     *
     * @param type 类型
     */
    protected void publish(final EventType type) {
        publish(type, null, handler);
    }

    /**
     * 发布事件
     *
     * @param type    类型
     * @param handler 处理器
     */
    protected void publish(final EventType type, final EventHandler handler) {
        publish(type, null, handler);
    }

    /**
     * 发布事件
     *
     * @param type      类型
     * @param throwable 异常
     * @param handler   处理器
     */
    protected void publish(final EventType type, final Throwable throwable, final EventHandler handler) {
        if (handler != null) {
            handler.handle(new StateEvent(type, throwable));
        }
    }

    /**
     * 状态Future
     *
     * @param <T>
     */
    public static class StateFuture<T> {
        /**
         * 打开的结果
         */
        protected volatile CompletableFuture<T> openFuture;
        /**
         * 关闭Future
         */
        protected volatile CompletableFuture<T> closeFuture;

        public StateFuture() {
            this(new CompletableFuture<>(), new CompletableFuture<>());
        }

        public StateFuture(CompletableFuture<T> openFuture, CompletableFuture<T> closeFuture) {
            this.openFuture = openFuture;
            this.closeFuture = closeFuture;
        }

        public CompletableFuture<T> getOpenFuture() {
            return openFuture;
        }

        public CompletableFuture<T> getCloseFuture() {
            return closeFuture;
        }

        /**
         * 创建打开Future
         *
         * @return 新建的打开Future
         */
        public CompletableFuture<T> newOpenFuture() {
            CompletableFuture<T> result = new CompletableFuture<>();
            openFuture = result;
            return result;
        }

        /**
         * 创建关闭Future
         *
         * @return 新建的关闭Future
         */
        public CompletableFuture<T> newCloseFuture() {
            CompletableFuture<T> result = new CompletableFuture<>();
            closeFuture = result;
            return result;
        }

        /**
         * 关闭
         */
        public void close() {
            if (openFuture != null) {
                openFuture.completeExceptionally(new InitializationException("state is illegal."));
            }
            if (closeFuture != null) {
                closeFuture.completeExceptionally(new IllegalStateException("Status is illegal."));
            }
        }
    }

    /**
     * 控制器
     */
    public interface Controller {

        /**
         * 打开
         *
         * @return CompletableFuture
         */
        CompletableFuture<Void> open();

        /**
         * 优雅关闭
         *
         * @param gracefully 优雅关闭标识
         * @return CompletableFuture
         */
        CompletableFuture<Void> close(boolean gracefully);

        /**
         * 关闭前中断等待
         */
        default void broken() {

        }
    }

    /**
     * 事件类型
     */
    public enum EventType {
        /**
         * 打开中
         */
        START_OPEN,
        /**
         * 打开
         */
        SUCCESS_OPEN,
        /**
         * 打开失败
         */
        FAIL_OPEN,
        /**
         * 打开状态异常
         */
        FAIL_OPEN_ILLEGAL_STATE,
        /**
         * 关闭中
         */
        START_CLOSE,
        /**
         * 关闭
         */
        SUCCESS_CLOSE;
    }

    /**
     * 状态事件
     */
    public static class StateEvent implements Event {
        /**
         * 事件类型
         */
        protected final EventType type;
        /**
         * 异常
         */
        protected final Throwable throwable;

        public StateEvent(EventType type) {
            this(type, null);
        }

        public StateEvent(EventType type, Throwable exception) {
            this.type = type;
            this.throwable = exception;
        }

        public EventType getType() {
            return type;
        }

        public Throwable getThrowable() {
            return throwable;
        }
    }

}
