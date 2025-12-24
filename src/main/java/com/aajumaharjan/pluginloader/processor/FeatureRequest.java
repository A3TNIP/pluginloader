package com.aajumaharjan.pluginloader.processor;

import java.util.Set;

/**
 * Immutable feature request derived from pluginloader configuration.
 */
public record FeatureRequest(String repository, String branch, Set<String> packages) {
}
