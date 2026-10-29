package com.youstream.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** A page of results; pass {@code nextCursor} back as {@code ?cursor=} for the next page (null = last page). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Page<T> {
    private List<T> items;
    private String nextCursor;
}
