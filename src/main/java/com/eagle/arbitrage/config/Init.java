package com.eagle.arbitrage.config;

import com.eagle.arbitrage.service.PairsContainer;
import com.eagle.arbitrage.service.TransactionHandler;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * 初始化
 */
@Configuration
@Slf4j
public class Init {

    @Value("${web3.ws.url}")
    private String web3WsUrl;

    @Bean
    public WebSocketClient initPairsContainer() throws URISyntaxException {
        try {
            WebSocketClient webSocketClient = new PairsContainer(new URI(web3WsUrl), new Draft_6455());
            webSocketClient.connect();
            return webSocketClient;
        } catch (Exception e) {
            log.error("初始化交易对处理器发生异常", e);
            throw e;
        }
    }

    @Bean
    public WebSocketClient initTransactionHandler() throws URISyntaxException {
        try {
            WebSocketClient webSocketClient = new TransactionHandler(new URI(web3WsUrl), new Draft_6455());
            webSocketClient.connect();
            return webSocketClient;
        } catch (Exception e) {
            log.error("初始化交易处理器发生异常", e);
            throw e;
        }
    }

}
