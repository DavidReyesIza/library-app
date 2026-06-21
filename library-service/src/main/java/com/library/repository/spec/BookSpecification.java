package com.library.repository.spec;

import com.library.model.Book;
import org.springframework.data.jpa.domain.Specification;

/**
 * Strategy pattern — each method returns an independent Specification that can be
 * combined with Specification.where().and() in BookService, avoiding a chain of
 * optional if-blocks in the query layer.
 */
public final class BookSpecification {

    private BookSpecification() {}

    public static Specification<Book> byAuthor(String author) {
        return (root, query, cb) ->
                cb.like(cb.lower(root.get("author")), "%" + author.toLowerCase() + "%");
    }

    public static Specification<Book> byGenre(String genre) {
        return (root, query, cb) ->
                cb.equal(cb.lower(root.get("genre")), genre.toLowerCase());
    }

    public static Specification<Book> isAvailable() {
        return (root, query, cb) ->
                cb.greaterThan(root.get("availableCopies"), 0);
    }
}
