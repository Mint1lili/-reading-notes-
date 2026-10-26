package com.readingnotes.dto.eval;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 评估失败项（fail_items_json 反序列化目标）
 */
@Data
public class FailItemDTO {

    @JsonProperty("item_id")
    private String itemId;

    private String category;

    private String question;

    private String expected;

    private String got;

    @JsonProperty("diff_note")
    private String diffNote;
}
