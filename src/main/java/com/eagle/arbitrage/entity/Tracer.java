package com.eagle.arbitrage.entity;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * @author: mowen.gx
 * @create: 2022-11-17 18:37
 * @Description: Tracer
 */
@Data
@Builder
public class Tracer implements Serializable {
    private Boolean enableMemory = true;
    private Boolean disableStack = false;
    private Boolean disableStorage = false;
    private Boolean enableReturnData = true;
}
