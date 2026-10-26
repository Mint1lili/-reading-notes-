package com.readingnotes.dto.eval;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 启动评估请求（接口 11.1 POST /api/v1/eval/run）
 */
@Data
public class EvalRunRequest {

    @NotBlank(message = "dataset_version 不能为空")
    @JsonProperty("dataset_version")
    private String datasetVersion;

    /** 评估类别：book_qa_simple / book_qa_located / book_out_of_scope / cross_book_qa / note_qa */
    private List<String> categories;

    /** 抽样题数，0 或 null 表示全量 */
    @JsonProperty("sample_size")
    @Min(value = 0, message = "sample_size 必须 >= 0")
    private Integer sampleSize;
}
