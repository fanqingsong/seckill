package io.servicecomb.poc.demo.seckill.es;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.servicecomb.poc.demo.seckill.entities.CouponEntity;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SecKillSearchIndex} 的 Elasticsearch 实现。{@code seckill.infra.mode=prod} 时由配置类创建。
 * <p>
 * 活动文档写在索引 {@code seckill-promotions}，券文档写在索引 {@code seckill-coupons}。
 * Event 服务投影时调用写入方法；Query 只有搜索接口调用 {@link #search}。
 * 列表和「我的券」读 Redis，不经过本类。本类用 JDK 的 HTTP 连接直接访问 REST，不扣库存，也不发 Kafka。
 */
public class HttpElasticsearchIndex implements SecKillSearchIndex {

  private static final Logger logger = LoggerFactory.getLogger(HttpElasticsearchIndex.class);
  /** Elasticsearch 根地址，例如 {@code http://127.0.0.1:9200}，末尾不保留斜杠。 */
  private final String baseUrl;
  private final ObjectMapper mapper = new ObjectMapper();

  /**
   * @param baseUrl {@code seckill.es.url}。若以 {@code /} 结尾，这里去掉，避免和后面的路径拼出双斜杠
   */
  public HttpElasticsearchIndex(String baseUrl) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  /**
   * PUT {@code /seckill-promotions/_doc/{promotionId}}，正文是活动 JSON。同一 id 再次写入会覆盖。
   *
   * @param promotion 活动开始事件里的活动
   */
  @Override
  public void indexPromotion(PromotionEntity promotion) {
    put("/seckill-promotions/_doc/" + promotion.getPromotionId(), promotion);
  }

  /**
   * PUT {@code /seckill-coupons/_doc/{promotionId}:{customerId}}，正文是券 JSON。
   *
   * @param coupon 要进入搜索索引的券
   */
  @Override
  public void indexCoupon(CouponEntity<String> coupon) {
    put("/seckill-coupons/_doc/" + coupon.getPromotionId() + ":" + coupon.getCustomerId(), coupon);
  }

  /**
   * POST {@code /seckill-promotions/_update/{promotionId}}，只把文档字段 {@code finished} 设为 true。
   * <p>
   * 不删除券索引里的文档，搜索仍能查到已结束活动的券。活动文档还不存在时，请求会按 HTTP 错误抛出。
   *
   * @param promotionId 活动编号，也是文档 id
   */
  @Override
  public void markPromotionFinished(String promotionId) {
    request("POST", "/seckill-promotions/_update/" + promotionId, "{\"doc\":{\"finished\":true}}");
  }

  /**
   * POST {@code /seckill-coupons/_search}，用 bool must 里的 term 查询顾客和活动。
   * <p>
   * 字段用 {@code customerId.keyword} 和 {@code promotionId.keyword}，按整段字符串匹配。
   * 两个条件都为空时改成 {@code match_all}。索引还不存在（HTTP 404）时返回空列表，
   * 这样 Event 服务还没写入第一张券时，搜索页得到空结果而不是异常。
   *
   * @param customerId 顾客编号。null 或空串则不加入 must
   * @param promotionId 活动编号。null 或空串则不加入 must
   * @return {@code hits.hits[]._source} 里的券。解析失败时返回已经收集到的列表，并打警告日志
   */
  @Override
  @SuppressWarnings("unchecked")
  public List<CouponEntity<String>> search(String customerId, String promotionId) {
    StringBuilder query = new StringBuilder("{\"query\":{\"bool\":{\"must\":[");
    boolean first = true;
    if (customerId != null && !customerId.isEmpty()) {
      query.append("{\"term\":{\"customerId.keyword\":\"").append(customerId).append("\"}}");
      first = false;
    }
    if (promotionId != null && !promotionId.isEmpty()) {
      if (!first) {
        // 两个 term 都要时，中间补逗号，否则 JSON 不合法。
        query.append(',');
      }
      query.append("{\"term\":{\"promotionId.keyword\":\"").append(promotionId).append("\"}}");
    }
    if (first && (promotionId == null || promotionId.isEmpty())) {
      // 顾客和活动都没给：上面的 bool 子句还是空的，整段换成匹配全部券文档。
      query = new StringBuilder("{\"query\":{\"match_all\":{}}");
    } else {
      query.append("]}}");
    }
    query.append('}');
    List<CouponEntity<String>> result = new ArrayList<CouponEntity<String>>();
    String body;
    try {
      body = request("POST", "/seckill-coupons/_search", query.toString());
    } catch (IllegalStateException e) {
      if (e.getMessage() != null && e.getMessage().contains("404")) {
        // 券索引尚未创建。搜索没有命中，而不是服务故障。
        return result;
      }
      throw e;
    }
    try {
      Map<?, ?> root = mapper.readValue(body, Map.class);
      Map<?, ?> hitsWrapper = (Map<?, ?>) root.get("hits");
      if (hitsWrapper == null) {
        return result;
      }
      List<?> hits = (List<?>) hitsWrapper.get("hits");
      if (hits == null) {
        return result;
      }
      for (Object hit : hits) {
        Map<?, ?> hitMap = (Map<?, ?>) hit;
        result.add(mapper.convertValue(hitMap.get("_source"), CouponEntity.class));
      }
    } catch (Exception e) {
      logger.warn("Failed to parse ES search response", e);
    }
    return result;
  }

  /**
   * 用 PUT 写入或覆盖一条文档。序列化失败或 HTTP 失败都包成 {@link IllegalStateException}。
   *
   * @param path 以斜杠开头的索引路径，含 {@code _doc} 和文档 id
   * @param body 活动或券对象
   */
  private void put(String path, Object body) {
    try {
      request("PUT", path, mapper.writeValueAsString(body));
    } catch (Exception e) {
      throw new IllegalStateException("ES index failed " + path, e);
    }
  }

  /**
   * 向 Elasticsearch 发一次带 JSON 正文的 HTTP 请求。
   * <p>
   * 状态码大于等于 400 时抛出 {@link IllegalStateException}，消息里带状态码和响应体，
   * 搜索方法靠其中的 {@code 404} 判断索引不存在。其它异常同样包成该运行时异常；
   * 已经是运行时异常的则原样抛出，避免再包一层。
   *
   * @param method HTTP 方法，写入用 PUT，更新和搜索用 POST
   * @param path 拼在 {@link #baseUrl} 后面的路径
   * @param json 请求体
   * @return 成功时的响应正文。调用方负责按 Elasticsearch 的 JSON 结构解析
   */
  private String request(String method, String path, String json) {
    HttpURLConnection connection = null;
    try {
      URL url = new URL(baseUrl + path);
      connection = (HttpURLConnection) url.openConnection();
      connection.setRequestMethod(method);
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "application/json");
      OutputStream output = connection.getOutputStream();
      output.write(json.getBytes(StandardCharsets.UTF_8));
      output.close();
      int code = connection.getResponseCode();
      InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
      StringBuilder response = new StringBuilder();
      if (stream != null) {
        byte[] buffer = new byte[2048];
        int read;
        while ((read = stream.read(buffer)) >= 0) {
          response.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        }
        stream.close();
      }
      if (code >= 400) {
        // 4xx/5xx 都视为失败。搜索路径单独识别 404。
        throw new IllegalStateException("ES HTTP " + code + " " + response);
      }
      return response.toString();
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }
}
