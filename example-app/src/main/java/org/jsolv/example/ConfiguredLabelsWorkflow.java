package org.jsolv.example;

import org.jsolv.PreservesTags;
import org.jsolv.example.tags.*;

/** Illustrates combining labels without listing every input/output combination. */
public final class ConfiguredLabelsWorkflow {
    public @Persisted Article persist(Article article) { return article; }

    @PreservesTags
    public @Validated Article validate(Article article) { return article; }

    @PreservesTags
    public @Authenticated Article authenticate(Article article) {
        // return article;
        // test with
        return new Article("authenticated " + article.title());
    }

    public void publish(@Persisted @Validated @Authenticated Article article) {
        System.out.println("Published " + article.title());
    }

    public void run() {
        Article draft = new Article("configured labels");
        Article ready = authenticate(validate(persist(draft)));
        publish(ready);
        // publish(validate(draft)); // missing Persisted and Authenticated
    }

    public void forward(@Persisted @Validated @Authenticated Article ready) {
        publish(ready); // Parameter contracts are known facts inside this method.
    }
}
