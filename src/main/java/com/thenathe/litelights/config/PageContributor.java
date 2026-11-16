package com.thenathe.litelights.config;

/** Adds rows to a settings page each time the screen builds it. See {@link Pages#contribute}. */
@FunctionalInterface
public interface PageContributor {
    void contribute(PageContent page);
}
