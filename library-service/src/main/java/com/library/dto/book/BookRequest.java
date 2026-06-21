package com.library.dto.book;

import jakarta.validation.constraints.*;

import java.time.Year;

public record BookRequest(

        @NotBlank(message = "Title is required")
        @Size(max = 300)
        String title,

        @NotBlank(message = "Author is required")
        @Size(max = 200)
        String author,

        @NotBlank(message = "ISBN is required")
        @Size(max = 20)
        String isbn,

        @NotNull(message = "Publication year is required")
        @Min(value = 1000, message = "Publication year must be at least 1000")
        @Max(value = 2100, message = "Publication year must be at most 2100")
        Integer publicationYear,

        @NotBlank(message = "Genre is required")
        @Size(max = 100)
        String genre,

        @NotNull(message = "Total copies is required")
        @Min(value = 1, message = "Total copies must be at least 1")
        Integer totalCopies
) {}
