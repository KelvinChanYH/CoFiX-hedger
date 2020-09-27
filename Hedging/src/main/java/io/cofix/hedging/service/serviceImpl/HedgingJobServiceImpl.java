package io.cofix.hedging.service.serviceImpl;

import com.huobi.model.trade.Order;
import io.cofix.hedging.constant.Constant;
import io.cofix.hedging.service.HedgingJobService;
import io.cofix.hedging.service.HedgingPoolService;
import io.cofix.hedging.service.HedgingService;
import io.cofix.hedging.service.TradeMarketService;
import io.cofix.hedging.vo.PoolAmountVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Calendar;
import java.util.TreeMap;
import java.util.function.Function;

@Component
@Slf4j
public class HedgingJobServiceImpl implements HedgingJobService {


    public static boolean START = false;

//    @Qualifier("mock")
    @Autowired
    private TradeMarketService tradeMarketService;

    @Qualifier("HBTC")
    @Autowired
    private HedgingPoolService hedgingHbtcPoolService;

    @Qualifier("USDT")
    @Autowired
    private HedgingPoolService hedgingUsdtPoolService;

    private boolean isAll(BigDecimal decimalOne, BigDecimal decimalTwo, Function<BigDecimal, Boolean> compare) {
        return compare.apply(decimalOne) && compare.apply(decimalTwo);
    }

    private boolean isAllNegative(BigDecimal decimalOne, BigDecimal decimalTwo) {
        return isAll(decimalOne, decimalTwo, decimal -> BigDecimal.ZERO.compareTo(decimal) >= 0);
    }

    private boolean isAllPositive(BigDecimal decimalOne, BigDecimal decimalTwo) {
        return isAll(decimalOne, decimalTwo, decimal -> BigDecimal.ZERO.compareTo(decimal) <= 0);
    }

    @Override
    public void hedgingPool(HedgingPoolService hedgingPoolService, TradeMarketService tradeMarketService) {
        // Huobi exchange price
        BigDecimal price = hedgingPoolService.getExchangePrice();

        if (null == price) {
            log.info("Get price from market failed." + hedgingPoolService.getSymbol());
            return;
        }

        // Total individual share
        BigInteger balance = hedgingPoolService.getBalance();
        // Always share
        BigInteger totalSupply = hedgingPoolService.getTotalSupply();
        // The eth total number
        BigInteger eth = hedgingPoolService.getEth();
        // The total number erc20
        BigInteger erc20 = hedgingPoolService.getErc20();
        // Erc20 digits
        BigInteger decimals = hedgingPoolService.getDecimals();

        PoolAmountVo newPoolAmountVo = new PoolAmountVo(balance, totalSupply, eth, erc20, decimals);

        log.info("PoolAmountVo：{}", newPoolAmountVo);

        if (balance == null || totalSupply == null
                || eth == null || erc20 == null
                || decimals == null) {
            return;
        }


        // It's 10 to the n
        BigInteger decimalsPowTen = BigInteger.TEN.pow(decimals.intValue());

        // Previous trading pool status
        PoolAmountVo oldPoolAmountVo = hedgingPoolService.getOldPoolAmountVo();

        hedgingPoolService.setOldPoolAmountVo(newPoolAmountVo);

        log.info(hedgingPoolService.getSymbol() + " " + newPoolAmountVo);

        // The first poll
        if (null == oldPoolAmountVo) {
            return;
        }

        // If there is no change, no processing is required.
        if (newPoolAmountVo.equals(oldPoolAmountVo)) return;

        BigDecimal myNewEth = newPoolAmountVo.getMyEth();
        BigDecimal myNewErc20 = newPoolAmountVo.getMyErc20();

        BigDecimal deltaEth = myNewEth.subtract(oldPoolAmountVo.getMyEth());
        BigDecimal deltaErc20 = myNewErc20.subtract(oldPoolAmountVo.getMyErc20());

        log.info("Current delta [" + deltaEth.toPlainString() + ", " + deltaErc20.toPlainString() + "]");

        hedgingPoolService.addDeltaEth(deltaEth);
        hedgingPoolService.addDeltaErc20(deltaErc20);

        // If the assets in the period are all positive or all negative, no treatment is required
        if (isAllNegative(deltaEth, deltaErc20) || isAllPositive(deltaEth, deltaErc20)) {
            return;
        }

        // If none of the thresholds are reached, no processing is required
        if ((deltaEth.abs().compareTo(hedgingPoolService.getEthThreshold()) < 0) &&
                (deltaErc20.abs().compareTo(hedgingPoolService.getErc20Threshold()) < 0)) {
            return;
        }

        BigDecimal deltaAccEth = hedgingPoolService.getDeltaEth();
        BigDecimal deltaAccErc20 = hedgingPoolService.getDeltaErc20();

        // Now you can start hedging
/*
        if (BigDecimal.ZERO.compareTo(deltaAccEth) > 0)  hedgingService.buyEth(); else hedgingService.sellEth();
        if (BigDecimal.ZERO.compareTo(deltaAccErc20) > 0)  hedgingService.buyErc20(); else hedgingService.sellErc20();
*/

        log.info("Enter the hedge. deltaAccEth["+deltaAccEth.toPlainString()+"] deltaAccErc20["+deltaAccErc20.toPlainString()+"]");

        // The ERC20 at the exchange price
        BigDecimal actualErc20 = deltaAccEth.abs()
                .divide(Constant.UNIT_ETH, 18, RoundingMode.HALF_UP)
                .multiply(price)
                .multiply(new BigDecimal(decimalsPowTen));

        log.info("actualErc20={}",actualErc20.toPlainString());

        // Number of ETH to be traded at the exchange price
        BigDecimal actualEth   = deltaAccErc20.abs()
                .divide(new BigDecimal(decimalsPowTen), decimals.intValue(), RoundingMode.HALF_UP)
                .divide(price, 18, BigDecimal.ROUND_HALF_UP)
                .multiply(Constant.UNIT_ETH);

        log.info("actualEth={}",actualEth.toPlainString());

        BigDecimal actualDealEth = (deltaAccErc20.abs().compareTo(actualErc20) > 0) ? deltaAccEth.abs() : actualEth;

        log.info("actualDealEth={}",actualDealEth.toPlainString());

        Long orderId;
        BigDecimal dealEth = actualDealEth.divide(Constant.UNIT_ETH, 18, BigDecimal.ROUND_DOWN);
        BigDecimal dealErc20 = dealEth.multiply(price);
        if (BigDecimal.ZERO.compareTo(deltaAccEth) > 0) { // Buy the ETH sell ERC20
            log.info("Buy the ETH sell ERC20");
            orderId = tradeMarketService.sendBuyMarketOrder(hedgingPoolService.getSymbol(), dealErc20.toPlainString());
        } else {    // Sell ETH  Buy  ERC20
            log.info("Sell ETH  Buy  ERC20 ");
            orderId = tradeMarketService.sendSellMarketOrder(hedgingPoolService.getSymbol(), dealEth.toPlainString());
        }

        // Sleep for two seconds and wait for the trade to complete
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // If the trade is successful, the delta is cleared, and if it is not successful, it accumulates to the next cycle
        Order order = tradeMarketService.getOrderById(orderId);

        // Completely to clinch a deal
        if ("filled".equals(order.getState())) {
            log.info("It's a complete deal. Let's clear the delta");
            hedgingPoolService.addDeltaEth(deltaAccEth.negate());
            hedgingPoolService.addDeltaErc20(actualErc20.negate());
        } else if ("partial-filled".equals(order.getState())) { // Some clinch a deal
            // It didn't quite work out, maybe it sold some of it, withdrew the order, and then delta subtracted the amount that it had already bought
            // The actual cancellation result still needs to query the order status
            log.info("For cancellations");
            tradeMarketService.cancelOrder(orderId);
            // Check again whether the order was cancelled successfully
            Order order2 = tradeMarketService.getOrderById(orderId);
            // After the withdrawal is completed
            if ("canceled".equals(order2.getState())
                    || "partial-canceled".equals(order2.getState())
                    || "filled".equals(order2.getState())) {

                // The order number
                BigDecimal amount = order2.getAmount();
                // Number of transactions
                BigDecimal filledAmount = order2.getFilledAmount();
                // Quantity sold
                if (filledAmount != null && amount != null) {
                    // The number of transactions that have been made
                    BigDecimal sellAmount = amount.subtract(filledAmount);
                    // Prevent the interface from returning 0
                    if (order2.getPrice() != null && order.getPrice().compareTo(BigDecimal.ZERO) > 0) {
                        price = order2.getPrice();
                    }

                    BigDecimal selledErc20 = sellAmount.multiply(price);

                    if (BigDecimal.ZERO.compareTo(deltaAccEth) > 0) {
                        selledErc20 = selledErc20.negate();
                    } else {
                        sellAmount = sellAmount.negate();
                    }

                    sellAmount = sellAmount.multiply(Constant.UNIT_ETH);
                    selledErc20 = selledErc20.multiply(new BigDecimal(decimalsPowTen));
                    log.info("Number of ETH transactions completed ={}, number of ERC20 ={}", sellAmount.toPlainString(), selledErc20.toPlainString());
                    hedgingPoolService.addDeltaEth(sellAmount);
                    hedgingPoolService.addDeltaErc20(selledErc20);

                }
            }
        }
    }


    /**
     * Polling hedge
     */
    @Override
    public void hedging() {
        if (!START) {
            log.info("Did not open");
            return;
        }
        log.info(Calendar.getInstance().getTime().toString() + " ==========Polling began==========");

        hedgingPool(hedgingHbtcPoolService, tradeMarketService);
        hedgingPool(hedgingUsdtPoolService, tradeMarketService);
    }
}