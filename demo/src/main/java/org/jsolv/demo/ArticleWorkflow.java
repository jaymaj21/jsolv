package org.jsolv.demo;

import org.jsolv.Persisted;

public final class ArticleWorkflow {
    public @Persisted Article persist(Article article) {
        return article;
    }

    public void publish(@Persisted Article article) {
        System.out.println("Published " + article.title());
    }

    public void good() {
        Article draft = new Article("static tags");
        Article saved = persist(draft);
        publish(saved);
        publish(persist(new Article("direct call")));
    }
}
