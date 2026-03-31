package com.example.javaparser.service.diagram;

import lombok.extern.slf4j.Slf4j;
import net.sourceforge.plantuml.SourceStringReader;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.FileFormat;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

@Slf4j
@Service
public class PlantUmlRenderer {

    @Value("${diagram.max-size:16384}")
    private int maxSize;

    @Value("${diagram.dpi:300}")
    private int dpi;

    @Value("${diagram.scale:1.0}")
    private double scale;

    @Value("${diagram.use-batik:true}")
    private boolean useBatik;

    @Value("${diagram.png-width:4096}")
    private float pngWidth;

    @PostConstruct
    public void init() {
        // PlantUML默认限制图片尺寸为4096x4096，大型项目会被截断
        // 提升到可配置的尺寸以支持大型图表
        System.setProperty("PLANTUML_LIMIT_SIZE", String.valueOf(maxSize));
        log.info("PlantUML图片尺寸限制已设置为 {}x{}", maxSize, maxSize);

        // 设置DPI以提高图片清晰度（默认96，提升到300）
        System.setProperty("PLANTUML_DPI", String.valueOf(dpi));
        log.info("PlantUML DPI已设置为 {}", dpi);

        log.info("PlantUML缩放因子: {}", scale);
        log.info("使用Batik高质量渲染: {}", useBatik);
        if (useBatik) {
            log.info("Batik PNG输出宽度: {} 像素", pngWidth);
        }
    }

    /**
     * 渲染为PNG格式（高分辨率）
     * 优先使用Batik渲染器（SVG->PNG高质量转换）
     * @param plantUmlText PlantUML文本
     * @return PNG字节数组
     */
    public byte[] renderToPng(String plantUmlText) {
        return renderToPng(plantUmlText, scale);
    }

    /**
     * 渲染为PNG格式（自定义缩放）
     * @param plantUmlText PlantUML文本
     * @param customScale 自定义缩放因子（1.0=原始大小，2.0=2倍大小）
     * @return PNG字节数组
     */
    public byte[] renderToPng(String plantUmlText, double customScale) {
        if (useBatik) {
            return renderPngViaBatik(plantUmlText, customScale);
        } else {
            return renderPngDirect(plantUmlText, customScale);
        }
    }

    /**
     * 使用Batik渲染高质量PNG（推荐方式）
     * 流程：PlantUML生成SVG -> Batik转换为高分辨率PNG
     */
    /**
     * Batik渲染时允许的最大像素总数（宽×高），防止OOM
     * 4096 * 4096 * 4 bytes ≈ 64MB，安全范围内
     */
    private static final long MAX_PIXEL_COUNT = 4096L * 4096L;

    private byte[] renderPngViaBatik(String plantUmlText, double customScale) {
        try {
            // 步骤1：生成SVG
            byte[] svgBytes = renderToSvg(plantUmlText);
            if (svgBytes.length == 0) {
                log.error("SVG生成失败，回退到直接PNG渲染");
                return renderPngDirect(plantUmlText, customScale);
            }

            // 步骤2：估算SVG宽高比，限制输出尺寸防止OOM
            float targetWidth = (float) (pngWidth * customScale);
            float targetHeight = estimateSvgHeight(svgBytes, targetWidth);

            // 如果预估像素总数超限，按比例缩小宽度
            if (targetHeight > 0) {
                long pixelCount = (long) targetWidth * (long) targetHeight;
                if (pixelCount > MAX_PIXEL_COUNT) {
                    float ratio = (float) Math.sqrt((double) MAX_PIXEL_COUNT / pixelCount);
                    float oldWidth = targetWidth;
                    targetWidth = targetWidth * ratio;
                    targetHeight = targetHeight * ratio;
                    log.warn("Batik渲染尺寸过大 ({}x{} = {}MP)，缩小到 {}x{} 以防止OOM",
                             (int) oldWidth, (int) (targetHeight / ratio),
                             pixelCount / 1_000_000,
                             (int) targetWidth, (int) targetHeight);
                }
            }

            // 步骤3：使用Batik将SVG转为PNG
            PNGTranscoder transcoder = new PNGTranscoder();
            transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, targetWidth);
            if (targetHeight > 0) {
                transcoder.addTranscodingHint(PNGTranscoder.KEY_MAX_HEIGHT, targetHeight);
            }

            // 设置高质量渲染
            transcoder.addTranscodingHint(PNGTranscoder.KEY_PIXEL_UNIT_TO_MILLIMETER, 0.084666f); // 300 DPI

            TranscoderInput input = new TranscoderInput(new ByteArrayInputStream(svgBytes));
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            TranscoderOutput output = new TranscoderOutput(outputStream);

            transcoder.transcode(input, output);

            byte[] result = outputStream.toByteArray();
            log.info("Batik PNG渲染完成 (width={}px, scale={}), 大小: {} KB",
                     targetWidth, customScale, result.length / 1024);
            return result;

        } catch (Throwable e) {
            log.error("Batik PNG渲染失败，回退到直接PNG渲染: {}", e.getMessage());
            return renderPngDirect(plantUmlText, customScale);
        }
    }

    /**
     * 从SVG内容中估算在给定宽度下的输出高度
     * 解析SVG的width/height或viewBox属性来计算宽高比
     */
    private float estimateSvgHeight(byte[] svgBytes, float targetWidth) {
        try {
            String svgHeader = new String(svgBytes, 0, Math.min(svgBytes.length, 2000), "UTF-8");

            // 尝试从viewBox获取宽高比: viewBox="x y width height"
            java.util.regex.Matcher vbMatcher = java.util.regex.Pattern
                    .compile("viewBox\\s*=\\s*\"[\\d.]+\\s+[\\d.]+\\s+([\\d.]+)\\s+([\\d.]+)\"")
                    .matcher(svgHeader);
            if (vbMatcher.find()) {
                float svgW = Float.parseFloat(vbMatcher.group(1));
                float svgH = Float.parseFloat(vbMatcher.group(2));
                if (svgW > 0) {
                    return targetWidth * (svgH / svgW);
                }
            }

            // 尝试从width/height属性获取
            java.util.regex.Matcher wMatcher = java.util.regex.Pattern
                    .compile("width\\s*=\\s*\"([\\d.]+)")
                    .matcher(svgHeader);
            java.util.regex.Matcher hMatcher = java.util.regex.Pattern
                    .compile("height\\s*=\\s*\"([\\d.]+)")
                    .matcher(svgHeader);
            if (wMatcher.find() && hMatcher.find()) {
                float svgW = Float.parseFloat(wMatcher.group(1));
                float svgH = Float.parseFloat(hMatcher.group(1));
                if (svgW > 0) {
                    return targetWidth * (svgH / svgW);
                }
            }
        } catch (Exception e) {
            log.debug("解析SVG尺寸失败: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * 直接使用PlantUML渲染PNG（备用方式）
     */
    private byte[] renderPngDirect(String plantUmlText, double customScale) {
        try {
            // 添加缩放指令到PlantUML文本
            String scaledText = addScaleDirective(plantUmlText, customScale);

            SourceStringReader reader = new SourceStringReader(scaledText);
            ByteArrayOutputStream os = new ByteArrayOutputStream();

            // 使用高DPI设置渲染
            FileFormatOption option = new FileFormatOption(FileFormat.PNG);
            reader.outputImage(os, option);

            byte[] result = os.toByteArray();
            log.debug("PlantUML直接PNG渲染完成 (scale={}, dpi={}), 大小: {} KB",
                     customScale, dpi, result.length / 1024);
            return result;
        } catch (Exception e) {
            log.error("PlantUML PNG渲染失败", e);
            return new byte[0];
        }
    }

    /**
     * 渲染为SVG格式（矢量图，无损缩放）
     * @param plantUmlText PlantUML文本
     * @return SVG字节数组
     */
    public byte[] renderToSvg(String plantUmlText) {
        try {
            SourceStringReader reader = new SourceStringReader(plantUmlText);
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            reader.outputImage(os, new FileFormatOption(FileFormat.SVG));
            byte[] result = os.toByteArray();
            log.debug("SVG渲染完成, 大小: {} KB", result.length / 1024);
            return result;
        } catch (Exception e) {
            log.error("PlantUML SVG渲染失败", e);
            return new byte[0];
        }
    }

    /**
     * 添加缩放指令到PlantUML文本
     * @param plantUmlText 原始PlantUML文本
     * @param scale 缩放因子
     * @return 添加缩放指令后的文本
     */
    private String addScaleDirective(String plantUmlText, double scale) {
        if (scale <= 0 || scale == 1.0) {
            return plantUmlText;
        }

        // 在@startuml后插入scale指令
        String scaleDirective = "scale " + scale + "\n";

        if (plantUmlText.contains("@startuml")) {
            return plantUmlText.replaceFirst("(@startuml[^\n]*\n)", "$1" + scaleDirective);
        } else {
            return "@startuml\n" + scaleDirective + plantUmlText + "\n@enduml";
        }
    }
}
