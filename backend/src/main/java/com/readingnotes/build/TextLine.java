package com.readingnotes.build;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * PDF 解析出的一个文本行（章节识别的原子输入）。
 * <p>
 * 设计说明：PDF 没有"段落"语义，只有字符和坐标。我们以"行"为单位保留排版信息
 * （字号、是否加粗、所在页），由 {@link ChapterRecognizer} 结合编号正则 + 字号显著性
 * 判定标题，由 {@link ChapterFlowBuilder} 依据字号变化/行距/句尾标点把行聚成段落。
 */
@Data
@AllArgsConstructor
public class TextLine {

    /** 行文本（已去除页眉页脚与首尾空白） */
    private String text;

    /** 起始页码（1-based） */
    private int page;

    /** 该行主要文字的字号（pt），用于标题显著性判定 */
    private float fontSize;

    /** 该行超过半数文字为粗体 */
    private boolean bold;

    /** 是否处于 PDF 的单栏垂直书写（竖排中文） */
    private boolean vertical;
}
