package com.eagle.arbitrage.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.eagle.arbitrage.common.enums.BizCodeEnum;
import com.eagle.arbitrage.common.exception.BizException;
import com.eagle.arbitrage.config.WalletConfig;
import com.eagle.arbitrage.config.Web3;
import com.eagle.arbitrage.contract.Pair;
import com.eagle.arbitrage.entity.Arb;
import com.eagle.arbitrage.entity.PairInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.ResourceUtils;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.tuples.generated.Tuple3;
import org.web3j.utils.Numeric;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * 通过WebSocket来进行交易对信息通信
 */
@Slf4j
public class PairsContainer extends WebSocketClient {

    /**
     * 线程池
     */
    private static ThreadPoolExecutor pairHandlerExecutor = new ThreadPoolExecutor(12, 24, 60, TimeUnit.MINUTES, new ArrayBlockingQueue<>(20000), new ThreadPoolExecutor.DiscardPolicy());
    private static ThreadPoolExecutor pairSyncExecutor = new ThreadPoolExecutor(4, 8, 60, TimeUnit.MINUTES, new ArrayBlockingQueue<>(20000), new ThreadPoolExecutor.DiscardPolicy());

    /**
     * 交易对信息文件
     */
    private static File pairsSerializeFile;

    /**
     * 交易对信息
     */
    public static Map<String, PairInfo> PAIRS;

    /**
     * 是否已同步
     */
    public static boolean SYNC = false;

    private String newLogsKey = "";

    private static BigInteger feeUnit = new BigInteger("10000");

    private static BigInteger baseFeeLeft = new BigInteger("9975");

    private static boolean skipNewPair = true;

    private static PairInfo BNB_BUSD_PAIR;


    /**
     * 构造函数初始化
     *
     * @param serverUri     以太坊WebSocket地址
     * @param protocolDraft WebSocket草案版本
     */
    public PairsContainer(URI serverUri, Draft protocolDraft) {
        super(serverUri, protocolDraft);
    }

    /**
     * 反序列化Pair
     */
    static {
        try {
            //从文件里边读取交易对信息列表
            pairsSerializeFile = new File("." + File.separator + "pairs.json");
            PAIRS = JSON.parseObject(FileUtils.readFileToString(pairsSerializeFile, StandardCharsets.UTF_8), new TypeReference<ConcurrentHashMap<String, PairInfo>>() {
            });
            BNB_BUSD_PAIR = PAIRS.get("0x16b9a82891338f9ba80e2d6970fdda79d1eb0dae");
        } catch (IOException e) {
            throw new BizException(BizCodeEnum.OPERATION_FAILED, "Pairs持久化文件读取失败");
        }
    }

    /**
     * 序列化pair（100分钟序列化一次）
     *
     * @throws IOException
     */
    @Scheduled(
            initialDelay = 6000 * 1000L, //初始启动间隔时长，单位毫秒
            fixedDelay = 6000 * 1000L //间隔时长，单位毫秒
    )
    public void pairsSerialize() throws IOException {
        FileUtils.writeStringToFile(pairsSerializeFile, JSON.toJSONString(PAIRS), StandardCharsets.UTF_8);

    }

    /**
     * 更新pair (1.启动时候更新 2.每10分钟完全更新一次)
     * //每隔10分钟，全量更新一次(基于已有的交易对信息)
     */
    @PostConstruct
    @Scheduled(initialDelay = 600 * 1000L, //初始启动间隔时长，单位毫秒
            fixedDelay = 600 * 1000L  //间隔时长，单位毫秒
    )
    public void pairsSync() {
        //异步执行
        new Thread(() -> {
            log.info("开始更新PairsReserves");
            Set<String> keySet = new HashSet<>(PAIRS.keySet());
            CountDownLatch countDownLatch = new CountDownLatch(keySet.size());
            //循环交易对信息进行更新
            for (String key : keySet) {
                PairInfo pairInfo = PAIRS.get(key);
                pairSyncExecutor.execute(() -> {
                    try {
                        //组装获取全量的交易对信息的请求
                        Pair pair = Pair.load(
                                pairInfo.getAddress(), //交易对合约的地址（需要调用合约里边的方法读取数据）
                                Web3.CLIENT, //Web3操作对象
                                WalletConfig.OPERATOR_CREDENTIALS,
                                null);
                        //发送请求，调用合约获取数据
                        Tuple3<BigInteger, BigInteger, BigInteger> reserves = pair.getReserves().send();
                        //检查数据是否合法，数据格式：Mask(112, 0, stor8.field_0), Mask(112, 0, stor8.field_0), uint32(stor8.field_224)
                        if (pairInfo.getReserve0().compareTo(reserves.component1()) != 0 || pairInfo.getReserve1().compareTo(reserves.component2()) != 0) {
                            pairInfo.setUpdateTime(LocalDateTime.now());
                            pairInfo.setReserve0(reserves.component1());
                            pairInfo.setReserve1(reserves.component2());
                        }
                    } catch (Exception e) {
                    } finally {
                        countDownLatch.countDown();
                    }
                });
            }
            try {
                countDownLatch.await();
            } catch (InterruptedException e) {
                log.error("同步全量交易对信息出现异常", e);
            }
            SYNC = true;
            log.info("PairsReserves更新完毕");
        }).start();
    }

    /**
     * WebSocket的打开链接事件回调
     *
     * @param data
     */
    @Override
    public void onOpen(ServerHandshake data) {
        //连接打开了之后，订阅SWAP事件消息
        subscribeLogs();
    }

    /**
     * 订阅dex中swap的logs消息（交易对的SWAP事件）
     */
    public void subscribeLogs() {
        HashMap<String, Object> request = new HashMap<>();
        List<Object> params = new ArrayList<>();
        params.add("logs");
        HashMap<String, List> topicParams = new HashMap<>();
        //订阅Sync事件
        topicParams.put("topics", Arrays.asList("0x1c411e9a96e071241c2f21f7726b17ae89e3cab4c78be50e062b03a9fffbbad1"));
        params.add(topicParams);
        request.put("params", params);
        request.put("id", "eth_subscribe_newLogs");
        request.put("method", "eth_subscribe");
        //通过WebSocket发送到Node订阅事件
        send(JSON.toJSONString(request));
    }

    /**
     * WebSocket的消息通知回调
     * 主要是处理SWAP的事件消息
     *
     * @param message
     */
    @Override
    public void onMessage(String message) {
        //解析推送过来的消息内容信息
        JSONObject result = JSON.parseObject(message);
        if (result.containsKey("id")) {
            //当前信息是否为订阅的新交易记录信息
            if ("eth_subscribe_newLogs".equals(result.get("id"))) {
                newLogsKey = result.getString("result");
            }
        }
        if (result.containsKey("method") && "eth_subscription".equals(result.getString("method"))) {
            JSONObject params = result.getJSONObject("params");
            if (newLogsKey.equals(params.getString("subscription"))) {
                //提交到线程池
                pairHandlerExecutor.execute(() -> handlerNewLogs(params.getJSONObject("result")));
            }
        }
    }


    /**
     * 从消息内容里边读取SWAP信息
     * 更新pair交易对
     */
    public void handlerNewLogs(JSONObject result) {
        //获取到订阅结果，解析成对象
        Log syncLog = result.toJavaObject(Log.class);
        if ("0xe26e436084348edc0d5c7244903dd2cd2c560f88".equals(syncLog.getAddress()) || "0x96f6eb307dcb0225474adf7ed3af58d079a65ec9".equals(syncLog.getAddress()) || "0xcdaf38ced8bf28ae3a0730dc180703cf794bea59".equals(syncLog.getAddress())) {
            //这个不是pair交易对!
            return;
        }

        //解析交易对里边的资金池储存数量信息
        List<BigInteger> reserves = decodeSync(syncLog.getData());
        //内存中的交易对信息是否包含了当前的交易对地址
        if (PAIRS.containsKey(syncLog.getAddress())) {
            //如果pair已经存在，则更新reserve信息
            //解析reserve信息
            PairInfo pairInfo = PAIRS.get(syncLog.getAddress());
            //更新代币Token1的资金储藏量
            pairInfo.setReserve0(reserves.get(0));
            //更新代币Token2的资金储藏量
            pairInfo.setReserve1(reserves.get(1));
            //更新交易对的时间
            pairInfo.setUpdateTime(LocalDateTime.now());
        }
    }

    /**
     * 解析交易对里边的资金池储存数量信息
     *
     * @param encodeData 消息实体
     * @return 返回交易对里边的资金池储存数量列表，列表中第一个为代币Token1的储存量，第二个为代币Token2的储存量
     */
    public List<BigInteger> decodeSync(String encodeData) {
        String data = Numeric.cleanHexPrefix(encodeData);
        List<BigInteger> result = new ArrayList<>();
        result.add(Numeric.toBigInt(data.substring(0, 64)));
        result.add(Numeric.toBigInt(data.substring(64)));
        return result;
    }

    /**
     * 计算交易对交易手续费
     */
    private static BigInteger calPairFee(BigInteger amount0In, BigInteger amount1In, BigInteger amount0Out, BigInteger amount1Out, BigInteger reserve0, BigInteger reserve1) {
        BigInteger amount0 = amount0In.subtract(amount0Out);
        reserve0 = reserve0.add(amount0Out).subtract(amount0In);
        reserve1 = reserve1.add(amount1Out).subtract(amount1In);
        if (amount0.compareTo(BigInteger.ZERO) > 0) {
            //amount0是输入
            return feeUnit.subtract(new BigDecimal(reserve0.multiply(amount1Out.abs()).multiply(feeUnit)).divide(new BigDecimal(amount0In.subtract(BigInteger.ONE).multiply(reserve1.subtract(amount1Out.abs()))), 0, RoundingMode.HALF_DOWN).toBigInteger());
        } else {
            return feeUnit.subtract(new BigDecimal(reserve1.multiply(amount0Out.abs()).multiply(feeUnit)).divide(new BigDecimal(amount1In.subtract(BigInteger.ONE).multiply(reserve0.subtract(amount0Out.abs()))), 0, RoundingMode.HALF_DOWN).toBigInteger());
        }
    }


    /**
     * 计算所有可进行的路径
     * 1、第一步匹配买入的代币Token
     * 2、匹配全部
     *
     * @param tokenIn    买入的代币
     * @param tokenOut   卖出的代币
     * @param maxHops    比配的最大跳数
     * @param targetPair 目标交易对
     * @param targetIn   目标买入
     * @param arb        中间临时存储的交易对信息
     * @param arbs       匹配的交易对信息列表
     */
    public static void findArb(String tokenIn, String tokenOut, Integer maxHops, PairInfo targetPair, String targetIn, List<PairInfo> arb, List<Arb> arbs) {
        log.info("PairsContainer.findArb");
        if (maxHops == 0) {
            return;
        }

        //循环全部交易对，进行寻找
        for (PairInfo currentPair : PAIRS.values()) {
            //前和后都没有配上输入的代币，则循环下一个交易对
            if (!currentPair.getToken0().equalsIgnoreCase(tokenIn) && !currentPair.getToken1().equalsIgnoreCase(tokenIn)) {
                //1.如果这个交易对没有pairIn的信息，认为是不相关交易对
                continue;
            }

            //如果当前pair已经在交易对中出现过则跳过
            if (arb.contains(currentPair)) {
                continue;
            }

            if (currentPair.equals(targetPair)) {
                //判断tokenIn是否一致
                if (!tokenIn.equalsIgnoreCase(targetIn)) {
                    //买入卖出方向不一致。。视为无效数据
                    continue;
                }
            }

            //叠加原来的交易对，诞生新分支
            ArrayList<PairInfo> newArb = new ArrayList<>();
            newArb.addAll(arb);
            newArb.add(currentPair);
            //计算tokenIn tokenOut分别是什么
            String tempOut = currentPair.getToken0().equalsIgnoreCase(tokenIn) ? currentPair.getToken1() : currentPair.getToken0();
            //确定是否已经找到合适的
            if (tempOut.equalsIgnoreCase(tokenOut) && newArb.contains(targetPair)) {
                //加入arbs中
                Arb targetArb = new Arb();
                targetArb.setPairs(newArb);
                //交易对的合约地址
                targetArb.setToken(tokenOut);
                arbs.add(targetArb);
                return;
            } else {
                //递归继续找
                findArb(tempOut, tokenOut, maxHops - 1, targetPair, targetIn, newArb, arbs);
            }
        }
        log.info("PairsContainer.findArb.end");
    }

    //获取bnb等价usdt
    public static BigInteger bnbExchange(BigInteger bnbAmountIn) {
        return getAmountOut("0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c", bnbAmountIn, BNB_BUSD_PAIR);
    }

    /**
     * 使用恒定乘积来计算输出拿到的Token的数量
     * @param tokenIn 输入的代表
     * @param amountIn 输入的代币数量
     * @param pair 交易对
     * @return 返回输出的代币数量
     */
    public static BigInteger getAmountOut(String tokenIn, BigInteger amountIn, PairInfo pair) {
        BigInteger reserveIn = pair.getToken0().equalsIgnoreCase(tokenIn) ? pair.getReserve0() : pair.getReserve1();
        BigInteger reserveOut = pair.getToken0().equalsIgnoreCase(tokenIn) ? pair.getReserve1() : pair.getReserve0();
        BigInteger amountInWithFee = amountIn.multiply(feeUnit.subtract(pair.getFee()));
        BigInteger numerator = amountInWithFee.multiply(reserveOut);
        BigInteger denominator = reserveIn.multiply(feeUnit).add(amountInWithFee);
        BigInteger amountOut = numerator.divide(denominator);
        return amountOut;
    }

    /**
     * 根据资金池得到交易对列表组成的虚拟交易对的资金池代币数量
     * @param tokenIn 输入资金池的代币Token（这里是稳定币）
     * @param pairs 交易对路径列表
     * @return
     */
    public static List<BigInteger> calEaEb(String tokenIn, List<PairInfo> pairs) {
        String tokenOut = null;
        BigInteger ea = null;
        BigInteger eb = null;
        for (int i = 0; i < pairs.size(); i++) {
            PairInfo pair = pairs.get(i);
            if (i == 0) {
                //首个交易对，初始的时候，需要找到买入的代币Token（In的是稳定币），找到对应拿出来的代币Token
                tokenOut = pair.getToken0().equalsIgnoreCase(tokenIn) ? pair.getToken1() : pair.getToken0();
            } else if (i == 1) {
                PairInfo firstPair = pairs.get(i - 1);
                //第一个交易对，收入的稳定币在资金池的总数
                BigInteger preInReserve = firstPair.getToken0().equalsIgnoreCase(tokenIn) ? firstPair.getReserve0() : firstPair.getReserve1();
                //第一个交易对，对应的输出代币的资金池数量
                BigInteger preOutReserve = firstPair.getToken0().equalsIgnoreCase(tokenIn) ? firstPair.getReserve1() : firstPair.getReserve0();
                //第二个交易对，输入的是上一轮的代币Token，这里读取资金池数量
                BigInteger currentInReserve = pair.getToken0().equalsIgnoreCase(tokenOut) ? pair.getReserve0() : pair.getReserve1();
                //第二个交易对，输出的Token的资金池数量
                BigInteger currentOutReserve = pair.getToken0().equalsIgnoreCase(tokenOut) ? pair.getReserve1() : pair.getReserve0();

                //denominator = 1000 * 当前轮次输入的代币资金池资金数量 + （（1000 - 交易手续费）* 上一轮的输出Token的资金池资金量）
                BigInteger denominator = feeUnit.multiply(currentInReserve).add(feeUnit.subtract(pair.getFee()).multiply(preOutReserve));
                // 1000 * 上一轮的输出Token的资金池资金量 * 当前轮次输入的代币资金池资金数量 / denominator
                ea = feeUnit.multiply(preInReserve).multiply(currentInReserve).divide(denominator);
                eb = feeUnit.subtract(pair.getFee()).multiply(preOutReserve).multiply(currentOutReserve).divide(denominator);

                //得到第二轮的时候，输出的代币Token
                tokenOut = pair.getToken0().equalsIgnoreCase(tokenOut) ? pair.getToken1() : pair.getToken0();
            } else {
                BigInteger ra = ea;//使用上一轮计算的结果
                BigInteger rb = eb;//使用上一轮计算的结果
                //上一轮的交易对的输出Token的资金池资金数量，也就是当前轮的输入Token的资金池资金数量
                BigInteger rb1 = pair.getToken0().equalsIgnoreCase(tokenOut) ? pair.getReserve0() : pair.getReserve1();
                //当前轮输出的Token资金池资金数量
                BigInteger rc = pair.getToken0().equalsIgnoreCase(tokenOut) ? pair.getReserve1() : pair.getReserve0();
                //输出的Token
                tokenOut = pair.getToken0().equalsIgnoreCase(tokenOut) ? pair.getToken1() : pair.getToken0();
                // denominator = 1000 * 当前轮次输入的代币资金池资金数量 + （(1000 - 手续费) * eb）
                BigInteger denominator = feeUnit.multiply(rb1).add(feeUnit.subtract(pair.getFee()).multiply(rb));
                //ea = （1000 * 使用上一轮计算的ea结果 * 当前轮的输入Token的资金池资金数量 ） / denominator
                ea = feeUnit.multiply(ra).multiply(rb1).divide(denominator);
                //ba = (（1000 - 手续费) * 使用上一轮计算的结果 *当前轮输出的Token资金池资金数量 ） / denominator
                eb = feeUnit.subtract(pair.getFee()).multiply(rb).multiply(rc).divide(denominator);
            }
        }
        List<BigInteger> result = new ArrayList<>();
        result.add(ea);
        result.add(eb);
        return result;
    }

    /**
     * 获取最理想的Amount输入值
     * 参考：https://github.com/ccyanxyz/uniswap-arbitrage-analysis/blob/master/readme_zh.pdf
     * @param tokenIn 输入的代币Token
     * @param pairs 交易对列表
     * @return 最理想的Amount输入值
     */
    public static BigInteger getOptimalAmount(String tokenIn, List<PairInfo> pairs) {
        List<BigInteger> eaEb = calEaEb(tokenIn, pairs);
        BigInteger ea = eaEb.get(0);
        BigInteger eb = eaEb.get(1);
        return getOptimalAmount(ea, eb);
    }

    /**
     * 根据虚拟交易对，求最佳的输入Token值
     * 参考：https://github.com/ccyanxyz/uniswap-arbitrage-analysis/blob/master/readme_zh.pdf
     * @param ea 虚拟交易对Token1的资金数量
     * @param eb 虚拟交易对Token2的资金数量
     * @return 返回虚拟交易对，的最佳输入Token数量
     */
    public static BigInteger getOptimalAmount(BigInteger ea, BigInteger eb) {
        if (ea.compareTo(eb) > 0) {
            return BigInteger.ZERO;
        }
        BigInteger optionAmount = (ea.multiply(eb).multiply(baseFeeLeft).multiply(feeUnit).sqrt().subtract(ea.multiply(feeUnit))).divide(baseFeeLeft);
        return optionAmount;
    }

    /**
     * 计算交易对列表中，每一个交易对的输出值
     * @param tokenIn 输入的代币
     * @param amountIn 输入的代币数量
     * @param pairs 交易对
     * @return 每一个交易对的输出值
     */
    public static List<BigInteger> getAmountsOut(String tokenIn, BigInteger amountIn, List<PairInfo> pairs) {
        List<BigInteger> amountsOut = new ArrayList<>();
        for (PairInfo pair : pairs) {
            //计算当前交易对在特定的输入下，可以拿到的代币输出量
            BigInteger amountOut = getAmountOut(tokenIn, amountIn, pair);
            amountsOut.add(amountOut);
            //得到下一轮的输入代币Token
            tokenIn = pair.getToken0().equalsIgnoreCase(tokenIn) ? pair.getToken1() : pair.getToken0();
            amountIn = amountOut;
        }
        return amountsOut;
    }

    /**
     * WebSocket的关闭事件回调
     *
     * @param code   状态码
     * @param reason 原因
     * @param remote
     */
    @Override
    public void onClose(int code, String reason, boolean remote) {
        log.info("PairsContainer.onClose");
    }

    /**
     * WebSocket的异常事件回调
     *
     * @param ex
     */
    @Override
    public void onError(Exception ex) {
        log.info("PairsContainer.onError");
    }

    /**
     * 定时任务，定时30秒检测链接状态，若断开后进行重连
     * 每30秒检测断开后重连
     */
    @Scheduled(initialDelay = 30_000, fixedDelay = 30_000)
    public void heartBeat() throws InterruptedException {
        if (getReadyState() == READYSTATE.OPEN) {
            //正常状态
        } else if (getReadyState() == READYSTATE.NOT_YET_CONNECTED) {
            //是否已经关闭连接
            if (isClosed()) {
                //调用WebSocket重连方法进行重连
                reconnectBlocking();
            } else {
                //非关闭，但状态异常，再次连接非重连
                connectBlocking();
            }
        } else if (getReadyState() == READYSTATE.CLOSED) {
            //重连
            reconnectBlocking();
        }
    }
}
