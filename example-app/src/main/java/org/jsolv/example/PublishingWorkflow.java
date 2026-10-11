package org.jsolv.example;

import org.jsolv.example.tags.Persisted;

public final class PublishingWorkflow {
    public @Persisted Article persist(Article article) {
        return article;
    }

    public void publish(@Persisted Article article) {
        System.out.println("Published " + article.title());
    }

	public Article modify(Article article)
	{
		return article;
	}

    public void publishDraft() {
        Article draft = new Article("annotation processor demo");
        Article saved = persist(draft);
        publish(saved);
        publish(persist(new Article("direct persisted call")));
		// Error:
		// publish(new Article("direct persisted call"));

		// Error:
		// saved = modify(saved);
		publish(saved);
    }


    public void branchExample(boolean alreadySaved) {
        Article article = new Article("branch demo");
        if (alreadySaved) {
            article = persist(article);
        } else {
            article = persist(new Article("alternate branch"));
        }
        publish(article);
    }

    public void badExample() {
        Article draft = new Article("unpersisted draft");
        Article saved = persist(draft);
        publish(saved);
    }
}
