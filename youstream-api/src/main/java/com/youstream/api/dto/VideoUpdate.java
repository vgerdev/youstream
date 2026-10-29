package com.youstream.api.dto;

import com.youstream.common.model.Visibility;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Metadata for completing an upload or editing a video; null fields are left unchanged. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VideoUpdate {
    private String title;
    private String description;
    private Visibility visibility;
}
