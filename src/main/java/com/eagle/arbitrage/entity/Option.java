package com.eagle.arbitrage.entity;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * @author: mowen.gx
 * @create: 2022-11-17 18:36
 * @Description: TraceOption
 */
@Data
@Builder
public class Option implements Serializable {
    private String tracer;
    private Boolean enableMemory;
    private Boolean disableStack;
    private Boolean disableStorage;
    private Boolean enableReturnData;
}
