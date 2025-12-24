package com.aajumaharjan.pluginloader.processor;

import java.util.Set;

/**
 * Captures results from scanning a feature repository.
 */
public record ScanResult(Set<String> packages, Set<String> beanClasses) {
}
