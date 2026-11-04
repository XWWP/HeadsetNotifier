package com.sakura.airannounce;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 播报文本清洗与格式化。
 *
 * 解决几个实际体验问题：
 * 1. 验证码数字被念成"八十四万六千二百九十一"
 * 2. 正文里的网址被完整念出来
 * 3. 表情符号造成发音怪异或停顿
 * 4. 长文本在词中间被硬切
 */
final class TextFormatter {
    /** 匹配"验证码 846291"这类结构，把数字拆开逐位朗读。 */
    private static final Pattern CODE_PATTERN = Pattern.compile(
            "(验证码|校验码|动态码|确认码|激活码|安全码|口令|code)[^0-9]{0,8}(\\d{4,8})",
            Pattern.CASE_INSENSITIVE);

    // 原来用 \S+ 收尾：中文正文里没有空白，「打开www.xxx.com查看账单」会把网址
    // 连同后面整句话一起删掉。改成只吃 URL 合法字符，句子其余部分保留。
    private static final Pattern URL_PATTERN = Pattern.compile(
            "(https?://|www\\.)[A-Za-z0-9./?=_&%#:+~,-]*", Pattern.CASE_INSENSITIVE);

    private static final Pattern MULTI_SPACE = Pattern.compile("[\\s\\u00A0]{2,}");

    /** 一句话结束的标点，用于按句截断。 */
    private static final String SENTENCE_END = "。！？!?；;…";

    private TextFormatter() {
    }

    /**
     * 清洗正文：去掉网址和表情符号，压缩空白。
     */
    static String cleanText(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String text = URL_PATTERN.matcher(input).replaceAll(" ");
        text = stripEmoji(text);
        text = MULTI_SPACE.matcher(text).replaceAll(" ");
        return text.trim();
    }

    /**
     * 删除表情符号、变体选择符、零宽连接符。
     */
    private static String stripEmoji(String text) {
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int charCount = Character.charCount(codePoint);
            if (!isEmojiLike(codePoint)) {
                builder.appendCodePoint(codePoint);
            }
            i += charCount;
        }
        return builder.toString();
    }

    private static boolean isEmojiLike(int codePoint) {
        // 变体选择符、零宽连接符
        if (codePoint == 0xFE0F || codePoint == 0x200D || codePoint == 0x20E3) {
            return true;
        }
        // 常见符号区、装饰区
        // 注意：0x2190–0x21FF 是箭头（→ ← ↑ ↓），属于正常文字符号，
        // 早前误按表情一起删除，会把「A → B」念成「A B」，这里不再删除。
        if (codePoint >= 0x2600 && codePoint <= 0x27BF) {
            return true;
        }
        if (codePoint >= 0x2B00 && codePoint <= 0x2BFF) {
            return true;
        }
        // Emoji 主体区
        if (codePoint >= 0x1F300 && codePoint <= 0x1FAFF) {
            return true;
        }
        // 区域指示符（国旗）
        if (codePoint >= 0x1F1E6 && codePoint <= 0x1F1FF) {
            return true;
        }
        return false;
    }

    /**
     * 验证码场景：把数字串拆成单个数字，让 TTS 逐位朗读。
     */
    static String expandVerificationCodes(String text) {
        Matcher matcher = CODE_PATTERN.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String digits = matcher.group(2);
            StringBuilder spaced = new StringBuilder(digits.length() * 2);
            for (int i = 0; i < digits.length(); i++) {
                if (i > 0) {
                    spaced.append(' ');
                }
                spaced.append(digits.charAt(i));
            }
            matcher.appendReplacement(buffer,
                    Matcher.quoteReplacement(matcher.group(1) + " " + spaced));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /**
     * 按句子边界截断，尽量避免切在词或句子中间。
     */
    static String truncateAtSentence(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        int floor = maxLength * 2 / 3;
        int cut = maxLength;
        for (int i = maxLength - 1; i >= floor; i--) {
            char c = text.charAt(i);
            if (SENTENCE_END.indexOf(c) >= 0 || c == '，' || c == ',') {
                cut = i + 1;
                break;
            }
        }
        String result = text.substring(0, cut).trim();
        if (!result.isEmpty()) {
            char last = result.charAt(result.length() - 1);
            if (SENTENCE_END.indexOf(last) < 0 && last != '，' && last != ',') {
                result = result + "…";
            }
        }
        return result;
    }

    /**
     * 组装最终播报文本。
     *
     * @param includeAppName 是否念应用名
     * @param includeTitle   是否念标题
     * @param includeBody    是否念正文
     * @param maxBodyLength  正文最大长度
     */
    static String compose(String appName, String title, String body,
            boolean includeAppName, boolean includeTitle, boolean includeBody,
            int maxBodyLength) {
        StringBuilder builder = new StringBuilder();
        if (includeAppName && appName != null && !appName.isEmpty()) {
            builder.append(appName);
        }
        if (includeTitle && title != null && !title.isEmpty()) {
            if (builder.length() > 0) {
                builder.append("。");
            }
            builder.append(title);
        }
        if (includeBody && body != null && !body.isEmpty() && !body.equals(title)) {
            String cleaned = truncateAtSentence(cleanText(body), maxBodyLength);
            if (!cleaned.isEmpty()) {
                if (builder.length() > 0) {
                    builder.append("。");
                }
                builder.append(cleaned);
            }
        }
        String result = expandVerificationCodes(builder.toString());
        return result.trim();
    }
}
