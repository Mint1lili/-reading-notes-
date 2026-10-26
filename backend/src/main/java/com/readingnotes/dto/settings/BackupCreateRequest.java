package com.readingnotes.dto.settings;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 创建备份请求（接口 10.5 POST）
 */
@Data
public class BackupCreateRequest {

    /** backup_type: auto / manual */
    @JsonProperty("backup_type")
    @Pattern(regexp = "^(auto|manual)$", message = "backup_type 必须为 auto 或 manual")
    private String backupType = "manual";

    /** trigger: scheduled / manual_click / pre_upgrade */
    @Pattern(regexp = "^(scheduled|manual_click|pre_upgrade)$", message = "trigger 取值不合法")
    private String trigger = "manual_click";
}
