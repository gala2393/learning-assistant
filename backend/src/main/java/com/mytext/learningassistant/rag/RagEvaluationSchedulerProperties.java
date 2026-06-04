package com.mytext.learningassistant.rag;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 评估调度器配置属性 —— 定义评估套件定时执行的各项参数。
 *
 * <p>通过此配置可以控制评估调度器是否启用以及扫描间隔。
 * 配置前缀：{@code app.rag-evaluation-scheduler}</p>
 *
 * <p>使用场景：用户可以创建评估套件并设置定时执行（如每天自动运行一次），
 * 调度器会按照配置的间隔扫描到期的评估套件并自动执行。</p>
 */
@ConfigurationProperties(prefix = "app.rag-evaluation-scheduler")
public class RagEvaluationSchedulerProperties {

    /** 是否启用评估调度器，默认开启 */
    private boolean enabled = true;

    /** 扫描间隔：每隔多久检查一次是否有到期需要执行的评估套件，默认 30 分钟 */
    private Duration scanInterval = Duration.ofMinutes(30);

    /**
     * 获取调度器是否启用。
     *
     * @return true 表示启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 获取扫描间隔时长。
     *
     * @return 扫描间隔
     */
    public Duration getScanInterval() {
        return scanInterval;
    }

    public void setScanInterval(Duration scanInterval) {
        this.scanInterval = scanInterval;
    }
}
