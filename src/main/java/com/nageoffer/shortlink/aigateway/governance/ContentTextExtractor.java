package com.nageoffer.shortlink.aigateway.governance;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 消息内容文本提取。
 * <p>
 * 消息 {@code content} 既可能是普通字符串，也可能是多模态内容块数组
 * （{@code [{"type":"text","text":"..."},{"type":"image_url",...}]}）。
 * 安全校验、token 预估、语义缓存都只关心其中的文本部分，因此统一收口在这里，
 * 避免每个使用方各自 instanceof 一遍。
 */
public final class ContentTextExtractor {

    private ContentTextExtractor() {
    }

    /**
     * 拼接内容中的所有文本片段；无文本时返回空串，绝不返回 null。
     */
    public static String text(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof CharSequence text) {
            return text.toString();
        }
        if (content instanceof Collection<?> parts) {
            StringBuilder builder = new StringBuilder();
            for (String each : texts(parts)) {
                builder.append(each);
            }
            return builder.toString();
        }
        return String.valueOf(content);
    }

    /**
     * 拆分内容中的所有文本片段，用于逐段做安全校验。
     */
    public static List<String> texts(Object content) {
        if (content == null) {
            return List.of();
        }
        if (content instanceof CharSequence text) {
            return List.of(text.toString());
        }
        if (content instanceof Collection<?> parts) {
            List<String> texts = new ArrayList<>();
            for (Object part : parts) {
                if (part instanceof Map<?, ?> partMap) {
                    Object text = partMap.get("text");
                    if (text != null) {
                        texts.add(String.valueOf(text));
                    }
                }
            }
            return texts;
        }
        return List.of(String.valueOf(content));
    }
}
