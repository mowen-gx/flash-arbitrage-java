package com.eagle.arbitrage.entity;

import lombok.Getter;
import lombok.Setter;

import java.math.BigInteger;
import java.time.LocalDateTime;

/**
 * 交易对信息
 */
@Getter
@Setter
public class PairInfo {

    /**
     * 交易对合约地址
     */
    private String address;

    /**
     * 交易对Token1
     */
    private String token0;

    /**
     * 交易对Token2
     */
    private String token1;

    /**
     * 交易对Token1的目前数量
     */
    private BigInteger reserve0;

    /**
     * 交易对Token2的目前数量
     */
    private BigInteger reserve1;

    /**
     * 交易对交易的手续费
     */
    private BigInteger fee;

    /**
     * 交易对创建时间
     */
    private LocalDateTime createTime;

    /**
     * 交易对更新时间
     */
    private LocalDateTime updateTime;

}
