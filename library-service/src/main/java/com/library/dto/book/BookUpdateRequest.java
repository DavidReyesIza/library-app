package com.library.dto.book;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record BookUpdateRequest(

        @Size(max = 300)
        String title,

        @Size(max = 200)
        String author,

        @Size(max = 20)
        String isbn,

        @Min(value = 1000, message = "Publication year must be at least 1000")
        @Max(value = 2100, message = "Publication year must be at most 2100")
        Integer publicationYear,

        @Size(max = 100)
        String genre,

        @Min(value = 1, message = "Total copies must be at least 1")
        Integer totalCopies
) {}
