package com.javaee.aiservice.rag;

import com.huaban.analysis.jieba.JiebaSegmenter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 统一文本分词器：用于 BM25 词粒度检索。
 *
 * 设计要点：
 * 1. 中文（及 CJK）走 jieba-analysis（jieba 的 Java 移植版），把"企业文档管理系统"切成
 *    ["企业", "文档", "管理", "系统"]，而不是按字或整句切分。
 * 2. 纯英文/数字走空白 + 标点切分，并统一小写；不调用 jieba，避免无意义开销。
 * 3. 语言分支用正则检测"是否含汉字"，无需重型语言识别模型。
 * 4. 分词异常时自动降级为空白切分，保证检索链路永不因分词失败而中断。
 * 5. jieba 实例懒加载：即使词典资源在特殊部署下加载失败，也只在该次请求降级，不影响应用启动。
 */
@Component
public class TextTokenizer {

    private static final Logger log = LoggerFactory.getLogger(TextTokenizer.class);

    private static final Pattern CJK_PATTERN = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    @Value("${rag.bm25.segment-cjk:true}")
    private boolean segmentCjk;

    private volatile JiebaSegmenter jiebaSegmenter;

    public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        try {
            if (segmentCjk && containsCjk(text)) {
                return tokenizeCjk(text);
            }
            return tokenizeWhitespace(text.toLowerCase());
        } catch (Exception e) {
            // jieba 加载/切词异常时的兜底：中文走按字切分（至少保证 BM25 非空），
            // 纯英文走空白切分。绝不让中文 query 因异常变成空 token 导致 BM25 整体失效。
            log.warn("分词失败，降级切分: {}", e.getMessage());
            return containsCjk(text) ? tokenizeByChar(text) : tokenizeWhitespace(text.toLowerCase());
        }
    }

    private List<String> tokenizeByChar(String text) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            if (!NON_WORD.matcher(ch).matches()) {
                tokens.add(ch);
            }
            i += Character.charCount(cp);
        }
        return tokens;
    }

    private boolean containsCjk(String text) {
        return CJK_PATTERN.matcher(text).find();
    }

    private List<String> tokenizeCjk(String text) {
        JiebaSegmenter seg = getJieba();
        List<String> raw = seg.sentenceProcess(text);
        List<String> tokens = new ArrayList<>(raw.size());
        for (String t : raw) {
            String norm = normalize(t);
            if (!norm.isEmpty()) {
                tokens.add(norm);
            }
        }
        return tokens;
    }

    private List<String> tokenizeWhitespace(String text) {
        String[] parts = NON_WORD.split(text);
        List<String> tokens = new ArrayList<>(parts.length);
        for (String p : parts) {
            if (!p.isEmpty()) {
                tokens.add(p);
            }
        }
        return tokens;
    }

    private String normalize(String token) {
        return NON_WORD.matcher(token.toLowerCase()).replaceAll("");
    }

    private JiebaSegmenter getJieba() {
        JiebaSegmenter seg = jiebaSegmenter;
        if (seg == null) {
            synchronized (this) {
                seg = jiebaSegmenter;
                if (seg == null) {
                    seg = new JiebaSegmenter();
                    jiebaSegmenter = seg;
                }
            }
        }
        return seg;
    }
}
