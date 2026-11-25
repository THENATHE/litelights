package com.thenathe.litelights.render.post;

/**
 * One step of the post chain, registered with {@link LitePostChain#register}. Called every frame
 * the level is drawn; a pass whose effect is off returns at once, so it costs nothing.
 */
@FunctionalInterface
public interface PostPass {
    void draw(PostContext context);
}
