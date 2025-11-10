package com.aajumaharjan.pluginloader.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class FeatureConfig {
    private String repository;
    private String branch;
    private List<String> packages;
}
