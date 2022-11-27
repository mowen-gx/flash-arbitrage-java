package com.eagle.arbitrage.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.eagle.arbitrage.config.WalletConfig;
import com.eagle.arbitrage.config.Web3;
import com.eagle.arbitrage.contract.Eagle;
import com.eagle.arbitrage.entity.Arb;
import com.eagle.arbitrage.entity.PairInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.beans.BeanUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Function;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.response.EthEstimateGas;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.utils.Numeric;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.eagle.arbitrage.config.Web3.GAS_PRICE;

@Slf4j
public class TransactionHandler extends WebSocketClient {

    /**
     * 线程池
     */
    private static ThreadPoolExecutor transactionHandlerExecutor = new ThreadPoolExecutor(4, 8, 60, TimeUnit.MINUTES, new SynchronousQueue<>(), new ThreadPoolExecutor.DiscardPolicy());

    private static BigInteger minGas = new BigInteger("100000");

    private String newPendingTransactionsKey = "";

    private static BigInteger feeUnit = new BigInteger("10000");

    public static List<String> targetTokens = new ArrayList<>();

    static {
        //WBNB
        targetTokens.add("0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c");
        //USDC
        targetTokens.add("0x8ac76a51cc950d9822d68b83fe1ad97b32cd580d");
        //USDT
        targetTokens.add("0x55d398326f99059ff775485246999027b3197955");
        //BUSD
        targetTokens.add("0xe9e7cea3dedca5984780bafc599bd69add087d56");
    }


    /**
     * 初始化构造函数
     *
     * @param serverUri     以太坊WebSocket地址
     * @param protocolDraft WebSocket草案
     */
    public TransactionHandler(URI serverUri, Draft protocolDraft) {
        super(serverUri, protocolDraft);
    }

    /**
     * WebSocket连接打开事件
     *
     * @param data 数据
     */
    @Override
    public void onOpen(ServerHandshake data) {
        //启动订阅待完成的交易信息
        subscribePendingTxs();
    }

    /**
     * 订阅Pending的交易的消息
     */
    public void subscribePendingTxs() {
        HashMap<String, Object> param = new HashMap<>();
        param.put("params", Arrays.asList("newPendingTransactions"));
        param.put("id", "eth_subscribe_newPendingTransactions");
        param.put("method", "eth_subscribe");
        log.info("订阅Pending的交易");
        send(JSON.toJSONString(param));
    }

    /**
     * WebSocket的消息事件
     *
     * @param message 消息实体
     */
    @Override
    public void onMessage(String message) {
        //解析消息实体
        JSONObject result = JSON.parseObject(message);
        if (result.containsKey("id")) {
            //当前消息是否为待完成交易
            if ("eth_subscribe_newPendingTransactions".equals(result.get("id"))) {
                //读取里边的pending信息内容
                newPendingTransactionsKey = result.getString("result");
            }
        }

        if (result.containsKey("method") && "eth_subscription".equals(result.getString("method"))) {
            JSONObject params = result.getJSONObject("params");
            //当前消息内容是否为订阅
            if (newPendingTransactionsKey.equals(params.getString("subscription"))) {
                handlerNewTxsWithTry(params.getString("result"));
                //提交到线程池
                transactionHandlerExecutor.execute(() -> {
                    handlerNewTxsWithTry(params.getString("result"));
                });
            }
        }
    }

    private void handlerNewTxsWithTry(String transactionHash) {
        try {
            //处理新的pending交易信息
            handlerNewTxs(transactionHash);
        } catch (IOException e) {
            //解析失败
            log.error("handlerNewTxs发生异常", e);
        }
    }

    /**
     * 处理pending的交易（pending状态的交易）
     */
    public void handlerNewTxs(String transactionHash) throws IOException {
        //交易对信息没有同步完成 先不处理
        if (!PairsContainer.SYNC) {
            return;
        }
        //通过哈希码调用RPC获取交易详细信息
        Optional<Transaction> transactionOptional = Web3.CLIENT.ethGetTransactionByHash(transactionHash).send().getTransaction();
        //是否获取到了交易信息
        if (!transactionOptional.isPresent()) {
            return;
        }
        //查询transaction详情
        Transaction transaction = transactionOptional.get();
        if (transaction == null) {
            return;
        }

        if ("0x".equals(transaction.getInput())) {
            return;
        }
        if (transaction.getGasPrice().compareTo(GAS_PRICE) < 0) {
            return;
        }
        if (transaction.getGas().compareTo(minGas) < 0) {
            return;
        }
        if (StringUtils.isEmpty(transaction.getTo())) {
            return;
        }

        //todo ??
        if (transaction.getBlockHash() == null) {
            return;
        }

        //这里只监听了1inch交易所
       /* if (transaction.getTo().equalsIgnoreCase("0xca4533591f5e5256f1bdb0f07fee3be76a1aae35")) {
            log.info(transactionHash);
        } else {
            return;
        }*/

        //tx 需要转为request
        org.web3j.protocol.core.methods.request.Transaction traceCallTx = org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(
                transaction.getFrom(),//调用方
                transaction.getTo(), //
                transaction.getInput());
        long startTime = System.currentTimeMillis();

        //调用方法 debug_traceCall，这里全部返回都为null
        //JSONObject traceCall = Web3.CLIENT.debugTraceCall(traceCallTx, DefaultBlockParameterName.PENDING).send().getResult();
        String res = "{\n" +
                "  " +
                " failed: false," +
                "gas: 85301,\n" +
                "  returnValue: \"\",\n" +
                "  structLogs: [{\n" +
                "      depth: 1,\n" +
                "      error: \"\",\n" +
                "      gas: 162106,\n" +
                "      gasCost: 3,\n" +
                "      memory: null,\n" +
                "      op: \"PUSH1\",\n" +
                "      pc: 0,\n" +
                "      stack: [],\n" +
                "      storage: {}\n" +
                "  },\n" +
                "    /* snip */\n" +
                "  {\n" +
                "      depth: 1,\n" +
                "      error: \"\",\n" +
                "      gas: 100000,\n" +
                "      gasCost: 0,\n" +
                "      memory: [\"0000000000000000000000000000000000000000000000000000000000000006\", \"0000000000000000000000000000000000000000000000000000000000000000\", \"0000000000000000000000000000000000000000000000000000000000000060\"],\n" +
                "      op: \"STOP\",\n" +
                "      pc: 120,\n" +
                "      stack: [\"00000000000000000000000000000000000000000000000000000000d67cbec9\"],\n" +
                "      storage: {\n" +
                "        0000000000000000000000000000000000000000000000000000000000000004: \"8241fa522772837f0d05511f20caa6da1d5a3209000000000000000400000001\",\n" +
                "        0000000000000000000000000000000000000000000000000000000000000006: \"0000000000000000000000000000000000000000000000000000000000000001\",\n" +
                "        f652222313e28459528d920b65115c16c04f3efc82aaedc97be59f3f377c0d3f: \"00000000000000000000000002e816afc1b5c0f39852131959d946eb3b07b5ad\"\n" +
                "      }\n" +
                "  }]}";
        JSONObject traceCall = JSON.parseObject(res);
        if (traceCall == null || traceCall.getBoolean("failed")) {
            return;
        }

        log.info("traceCall耗时：{}，structLogs ： {}", System.currentTimeMillis() - startTime, JSON.toJSONString(traceCall));
        //从获得的实体类中解析数据
        JSONArray structLogs = traceCall.getJSONArray("structLogs");
        //
        /*if (structLogs.size() < 3000) {
            return;
        }*/

        //循环处理
        for (int i = 0; i < structLogs.size(); i++) {
            JSONObject structLog = structLogs.getJSONObject(i);
            //从数组中读取stack
            JSONArray pairStack = structLog.getJSONArray("stack");
            //TODO ??
            /*if (pairStack.size() < 8) {
                continue;
            }*/

            String functionCode = pairStack.getString(pairStack.size() - 1);
            //TODO ??
            if (!"0x22c0d9f".equals(functionCode)
                    && !"0x6a627842".equals(functionCode)
                    && !"0x89afcb44".equals(functionCode)) {
                continue;
            }
            //找到目标pairStack;
            String pairAddress = "";
            //在SWAP信息中找到交易对信息
            for (int j = pairStack.size() - 1; j >= 0; j--) {
                String tempStackValue = pairStack.getString(j);
                if (tempStackValue.length() == 42) {
                    pairAddress = tempStackValue;
                    break;
                }
            }

            log.info("findPair耗时：{}", System.currentTimeMillis() - startTime);
            //沒有匹配上交易对
            if (!PairsContainer.PAIRS.containsKey(pairAddress)) {
                continue;
            }

            //向下找sync函数
            for (int j = i; j < structLogs.size(); j++) {
                JSONObject syncStructLog = structLogs.getJSONObject(j);
                JSONArray syncStack = syncStructLog.getJSONArray("stack");
                if (syncStack.size() < 10) {
                    continue;
                }
                if (!functionCode.equals(syncStack.getString(0))) {
                    continue;
                }

                //TODO ??
                if (!"0x1c411e9a96e071241c2f21f7726b17ae89e3cab4c78be50e062b03a9fffbbad1".equals(syncStack.getString(syncStack.size() - 1))) {
                    //Sync方法函数
                    continue;
                }
                //找到目标sync方法参数
                log.info(syncStack.toString());
                //解析reserve信息
                int startIndex = syncStack.indexOf("0xa4");
                if (startIndex == -1) {
                    continue;
                }
                log.info("findSync耗时：{}", System.currentTimeMillis() - startTime);
                //从数据中读取两种代SWAP之后的数量
                BigInteger reserve0Before = Numeric.toBigInt(syncStack.getString(startIndex + 2));
                BigInteger reserve1Before = Numeric.toBigInt(syncStack.getString(startIndex + 3));
                BigInteger reserve0After = Numeric.toBigInt(syncStack.getString(startIndex + 4));
                BigInteger reserve1After = Numeric.toBigInt(syncStack.getString(startIndex + 5));
                log.info(reserve0After.toString());

                //当前Swap的交易对
                PairInfo currentSwapPair = PairsContainer.PAIRS.get(pairAddress);
                //如果输入资金池和从资金池拿走的，都是稳定币交易，则放弃
                if (targetTokens.contains(currentSwapPair.getToken0()) && targetTokens.contains(currentSwapPair.getToken1())) {
                    continue;
                }
                //获取tokenIn & tokenOut （SWAP后变多的是从资金池拿出来的）
                String currentSwapTokenOut = reserve0After.compareTo(reserve0Before) >= 0 ? currentSwapPair.getToken1() : currentSwapPair.getToken0();
                //获取最佳交易对
                List<Arb> arbs = new ArrayList<>();
                CountDownLatch countDownLatch = new CountDownLatch(targetTokens.size());


                //寻找从稳定币开始，经过目标Token，再回到稳定币的闭环路径
                for (String stableToken : targetTokens) {
                    //多线程寻找可套利的交易对路径
                    new Thread(() -> {
                        PairsContainer.findArb(
                                stableToken, //此入参为输入交易所的是稳定币，初次调用的时候使用稳定币
                                stableToken, //此入参为最终路径闭环的代币也就是最初的稳定币，初次调用的时候使用稳定币（需要形成闭环，所以初始输入的in和out是一样的）
                                2, //可以匹配的最大跳数
                                currentSwapPair,
                                currentSwapTokenOut,//当前交易对里边的从DEX资金池拿出来的代币（非稳定币）
                                new ArrayList<>(), //临时存储交易对信息
                                arbs //最终输出结果
                        );
                        countDownLatch.countDown();
                    }).start();
                }

                //等到计算完毕
                try {
                    countDownLatch.await();
                } catch (InterruptedException e) {
                    return;
                }

                log.info("findArb耗时：{}", System.currentTimeMillis() - startTime);
                log.info(JSON.toJSONString(arbs));

                //使用所有交易对计算最优价格
                Arb attackArb = null;
                BigInteger optimalAmount = BigInteger.ZERO;
                BigInteger optimalAmountOut = BigInteger.ZERO;
                BigInteger optimalProfitAmount = BigInteger.ZERO;

                //循环交易对路径进行计算
                for (Arb arb : arbs) {
                    List<PairInfo> pairs = arb.getPairs();
                    //更新目标reserve信息
                    for (int k = 0; k < pairs.size(); k++) {
                        if (pairs.get(k).getAddress().equals(pairAddress)) {
                            PairInfo pairInfo = new PairInfo();
                            //之所以要Copy出来可能是防止多线程导致数据问题
                            BeanUtils.copyProperties(pairs.get(k), pairInfo);
                            //将更新后reserve赋值
                            pairInfo.setReserve0(reserve0After);
                            pairInfo.setReserve1(reserve1After);
                            pairs.set(k, pairInfo);
                            log.info(JSON.toJSONString(PairsContainer.PAIRS.get(pairAddress)));
                            break;
                        }
                    }

                    //计算tokenIn
                    //String tokenIn = targetTokens.contains(arb.get(0).getToken0()) ? arb.get(0).getToken0() : arb.get(0).getToken1();
                    //获取最佳的输入的值（组成交易对）
                    // 参考：https://github.com/ccyanxyz/uniswap-arbitrage-analysis/blob/master/readme_zh.pdf
                    BigInteger tempOptimalAmount = PairsContainer.getOptimalAmount(arb.getToken()/*输入的Token，以稳定币开始*/, pairs/*实现SWAp的路径*/);
                    if (tempOptimalAmount.compareTo(BigInteger.ZERO) <= 0) {
                        continue;
                    }

                    //使用恒定乘积来执行每一个交易对的交还，得到最终输出的Token的数量
                    List<BigInteger> amountsOut = PairsContainer.getAmountsOut(arb.getToken()/*输入的Token*/, tempOptimalAmount/*输入Token数量*/, pairs/*交易对列表*/);
                    //得到交易对最后输出代币值数量
                    BigInteger tempOptimalAmountOut = amountsOut.get(amountsOut.size() - 1);
                    //计算盈利金额
                    BigInteger tempProfitAmount;
                    //若是币安币的话
                    if ("0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c".equals(arb.getToken())) {
                        //BNB的话需要转换成usdt
                        tempProfitAmount = PairsContainer.bnbExchange(tempOptimalAmountOut.subtract(tempOptimalAmount));
                    } else {
                        tempProfitAmount = tempOptimalAmountOut.subtract(tempOptimalAmount);
                    }

                    //输入代币交换完了只有比和原来的代币数量大的话，就可能有利润空间
                    if (tempProfitAmount.compareTo(optimalProfitAmount) > 0) {
                        attackArb = arb;
                        optimalAmount = tempOptimalAmount;
                        optimalAmountOut = tempOptimalAmountOut;
                        optimalProfitAmount = tempProfitAmount;
                    }
                }

                //存在盈利空间
                if (optimalProfitAmount.compareTo(BigInteger.ZERO) > 0) {
                    //log.info("查找入侵交易对耗时：{}", System.currentTimeMillis() - startTime);
                    //计算amountOutMin
                    BigInteger amountOutMin = optimalAmount;
                    if (attackArb.getToken().equals("0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c")) {
                        //BNB，TODO ？？ 为啥这里return 了
                        amountOutMin = amountOutMin.add(WalletConfig.baseGasFeeBNB);
                        return;
                    } else {
                        amountOutMin = amountOutMin.add(WalletConfig.baseGasFeeUSDT);
                    }

                    if (optimalAmount.compareTo(new BigInteger("360000000000000000000")) > 0) {
                        return;
                    }

                    if (optimalAmountOut.compareTo(amountOutMin) >= 0) {
//                        //拼装下单参数
//                        Function huntingFunction = Eagle.hunting_v1Function(optimalAmount, amountOutMin, attackArb.getToken(), attackArb.getPairs().stream().map(x -> x.getFee()).collect(Collectors.toList()), attackArb.getPairs().stream().map(x -> x.getAddress()).collect(Collectors.toList()));
//                        //Function huntingFunction = Eagle.hunting_v2Function(optimalAmount, attackArb.getToken(), attackArb.getPairs().stream().map(x -> x.getFee()).collect(Collectors.toList()), attackArb.getPairs().stream().map(x -> x.getAddress()).collect(Collectors.toList()));
//                        String data = FunctionEncoder.encode(huntingFunction);
//                        RawTransaction rawTransaction = RawTransaction.createTransaction(
//                                WalletConfig.getOperatorNonce(),
//                                transaction.getGasPrice(),
//                                WalletConfig.gasLimit,
//                                WalletConfig.contractAddress, data);
//                        //交易簽名
//                        String encodeTx = Numeric.toHexString(TransactionEncoder.signMessage(rawTransaction, WalletConfig.chainId, WalletConfig.OPERATOR_CREDENTIALS));
//                        //發送交易
//                        EthSendTransaction huntingResult = Web3.CLIENT.ethSendRawTransaction(encodeTx).send();
//                        log.info("targetHash:{},sendSuccessHash:{}", transactionHash, huntingResult.getTransactionHash());

//                        //GAS费计算，下单逻辑
                        Function huntingEstimateGasFunction = Eagle.hunting_v1Function(optimalAmount, BigInteger.ZERO, attackArb.getToken(), attackArb.getPairs().stream().map(x -> x.getFee()).collect(Collectors.toList()), attackArb.getPairs().stream().map(x -> x.getAddress()).collect(Collectors.toList()));
                        //Function huntingFunction = Eagle.hunting_v2Function(optimalAmount, attackArb.getToken(), attackArb.getPairs().stream().map(x -> x.getFee()).collect(Collectors.toList()), attackArb.getPairs().stream().map(x -> x.getAddress()).collect(Collectors.toList()));
                        String estimateGasData = FunctionEncoder.encode(huntingEstimateGasFunction);
                        org.web3j.protocol.core.methods.request.Transaction huntingEsGasTx = org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(WalletConfig.OPERATOR_CREDENTIALS.getAddress(), WalletConfig.contractAddress, estimateGasData);
                        EthEstimateGas result = Web3.CLIENT.ethEstimateGas(huntingEsGasTx).send();
                        Response.Error error = result.getError();
                        if (ObjectUtils.isNotEmpty(error)) {
                            log.info("EstimateGasError:{}", JSON.toJSONString(error));
                            log.info("EstimateGas耗时：{}", System.currentTimeMillis() - startTime);
                            return;
                        }

                        BigInteger gasUsed = result.getAmountUsed();
                        //需要x2 因为SELF DESTRUCT 会节省一半GAS费
                        BigInteger gasFee = gasUsed.multiply(transaction.getGasPrice()).multiply(BigInteger.TWO);
                        //gasFee 转为USDT
                        gasFee = PairsContainer.bnbExchange(gasFee);
                        //判断是否覆盖盈利
                        if (optimalProfitAmount.compareTo(gasFee) > 0) {
                            //盈利金额大于gas费
                            //拼裝下單參數
                            Function huntingFunction = Eagle.hunting_v1Function(optimalAmount, optimalAmount, attackArb.getToken(), attackArb.getPairs().stream().map(x -> x.getFee()).collect(Collectors.toList()), attackArb.getPairs().stream().map(x -> x.getAddress()).collect(Collectors.toList()));
                            //Function huntingFunction = Eagle.hunting_v2Function(optimalAmount, attackArb.getToken(), attackArb.getPairs().stream().map(x -> x.getFee()).collect(Collectors.toList()), attackArb.getPairs().stream().map(x -> x.getAddress()).collect(Collectors.toList()));
                            String data = FunctionEncoder.encode(huntingFunction);
                            RawTransaction rawTransaction = RawTransaction.createTransaction(
                                    WalletConfig.getOperatorNonce(),
                                    transaction.getGasPrice(),
                                    WalletConfig.gasLimit,
                                    WalletConfig.contractAddress, data);
                            //交易簽名
                            String encodeTx = Numeric.toHexString(TransactionEncoder.signMessage(rawTransaction, WalletConfig.chainId, WalletConfig.OPERATOR_CREDENTIALS));
                            //發送交易
                            EthSendTransaction huntingResult = Web3.CLIENT.ethSendRawTransaction(encodeTx).send();
                            log.info("targetHash:{},sendSuccessHash:{}", transactionHash, huntingResult.getTransactionHash());
                        }
                    }
                }
                i = j;
                break;
            }
        }
    }

    /**
     * WebSocket的连接关闭事件
     *
     * @param code   到吗
     * @param reason 原因
     * @param remote
     */
    @Override
    public void onClose(int code, String reason, boolean remote) {
        log.info("TransactionHandler.close");
    }

    /**
     * WebSocket的错误事件
     *
     * @param ex 异常信息
     */
    @Override
    public void onError(Exception ex) {
        log.error("TransactionHandler.error", ex);
    }

    /**
     * 每30秒检测断开后重连
     */
    @Scheduled(initialDelay = 30_000, fixedDelay = 30_000)
    public void heartBeat() throws InterruptedException {
        if (getReadyState() == READYSTATE.OPEN) {
        } else if (getReadyState() == READYSTATE.NOT_YET_CONNECTED) {
            if (isClosed()) {
                reconnectBlocking();
            } else {
                connectBlocking();
            }
        } else if (getReadyState() == READYSTATE.CLOSED) {
            reconnectBlocking();
        }
    }

}
