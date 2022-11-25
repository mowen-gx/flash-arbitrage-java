package com.eagle.arbitrage.entity;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class Arb {

    /**
     *
     */
    private String token;

    /**
     * 交易对路径信息
     */
    List<PairInfo> pairs;

}
