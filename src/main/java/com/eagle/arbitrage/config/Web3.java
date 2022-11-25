package com.eagle.arbitrage.config;

import com.eagle.arbitrage.service.PairsContainer;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.math.BigInteger;

@Configuration
public class Web3 {

    //客户端
    public static Web3Client CLIENT = new Web3Client(new HttpService("wss://eth-mainnet.g.alchemy.com/v2/Z_o2kAR_GFo2GnYl3s3RLktflk6TnZtg"));

    public static BigInteger GAS_PRICE;

    @PostConstruct
    @Scheduled(initialDelay = 60 * 1000L, fixedDelay = 60 * 1000L)
    public void updateGasPrice() throws IOException {
        GAS_PRICE = CLIENT.ethGasPrice().send().getGasPrice();
        WalletConfig.baseGasFeeBNB = GAS_PRICE.multiply(new BigInteger("200000"));
        WalletConfig.baseGasFeeUSDT = PairsContainer.bnbExchange(WalletConfig.baseGasFeeBNB);
    }

}
