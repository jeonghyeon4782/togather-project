package com.common.togather.api.request;

import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class BookmarkDateUpdateRequest {

    private LocalDate date;

}
