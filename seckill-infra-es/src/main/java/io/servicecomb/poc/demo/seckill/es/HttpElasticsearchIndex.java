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

public class HttpElasticsearchIndex implements SecKillSearchIndex {

  private static final Logger logger = LoggerFactory.getLogger(HttpElasticsearchIndex.class);
  private final String baseUrl;
  private final ObjectMapper mapper = new ObjectMapper();

  public HttpElasticsearchIndex(String baseUrl) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  @Override
  public void indexPromotion(PromotionEntity promotion) {
    put("/seckill-promotions/_doc/" + promotion.getPromotionId(), promotion);
  }

  @Override
  public void indexCoupon(CouponEntity<String> coupon) {
    put("/seckill-coupons/_doc/" + coupon.getPromotionId() + ":" + coupon.getCustomerId(), coupon);
  }

  @Override
  public void markPromotionFinished(String promotionId) {
    request("POST", "/seckill-promotions/_update/" + promotionId, "{\"doc\":{\"finished\":true}}");
  }

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
        query.append(',');
      }
      query.append("{\"term\":{\"promotionId.keyword\":\"").append(promotionId).append("\"}}");
    }
    if (first && (promotionId == null || promotionId.isEmpty())) {
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

  private void put(String path, Object body) {
    try {
      request("PUT", path, mapper.writeValueAsString(body));
    } catch (Exception e) {
      throw new IllegalStateException("ES index failed " + path, e);
    }
  }

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
