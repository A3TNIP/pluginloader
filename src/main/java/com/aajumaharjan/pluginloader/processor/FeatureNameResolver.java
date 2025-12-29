package com.aajumaharjan.pluginloader.processor;

import java.util.Set;

class FeatureNameResolver {
    public String deriveFeatureName(String repoUrl, String pkgHint, Set<String> beanClasses) {
        if (pkgHint != null && !pkgHint.isBlank()) {
            String[] parts = pkgHint.split("\\.");
            return capitalize(parts[parts.length - 1]);
        }
        if (beanClasses != null && !beanClasses.isEmpty()) {
            String any = beanClasses.iterator().next();
            String[] parts = any.split("\\.");
            return capitalize(parts[parts.length - 1]);
        }
        String name = repoUrl;
        int slash = Math.max(repoUrl.lastIndexOf('/'), repoUrl.lastIndexOf('\\'));
        if (slash != -1 && slash + 1 < repoUrl.length()) name = repoUrl.substring(slash + 1);
        if (name.endsWith(".git")) name = name.substring(0, name.length() - 4);
        return capitalize(name.replaceAll("[^A-Za-z0-9]", ""));
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
