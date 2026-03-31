package com.example.javaparser.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 图表渲染配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "diagram")
public class DiagramProperties {

    /**
     * PlantUML最大图片尺寸（像素）
     * 默认4096，推荐16384用于大型项目
     */
    private Integer maxSize = 16384;

    /**
     * 图片DPI（影响PNG清晰度）
     * 默认96，推荐300用于高清输出
     */
    private Integer dpi = 300;

    /**
     * 默认缩放因子
     * 1.0=原始大小，大型项目推荐1.5-2.0
     */
    private Double scale = 1.0;

    /**
     * 是否使用Batik进行高质量PNG渲染
     * true=使用Batik（SVG->PNG，高质量），false=使用PlantUML直接渲染
     * 默认true，推荐启用
     */
    private Boolean useBatik = true;

    /**
     * Batik PNG输出宽度（像素）
     * 默认4096，大型项目推荐8192或更高
     * 高度会自动按比例计算
     */
    private Float pngWidth = 4096f;
}
