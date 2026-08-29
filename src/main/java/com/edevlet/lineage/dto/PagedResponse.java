package com.edevlet.lineage.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * A stable page envelope for list endpoints.
 *
 * <p>Spring Data's {@code Page} serialises its internal {@code Pageable} and {@code Sort} shape
 * into the response body, which is not an API contract this service wants to own. This record is,
 * and it keeps the paging fields a client actually needs.
 */
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last) {

    public static <E, T> PagedResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PagedResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast());
    }
}
