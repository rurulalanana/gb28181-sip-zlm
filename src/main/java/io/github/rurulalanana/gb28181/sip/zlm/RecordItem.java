package io.github.rurulalanana.gb28181.sip.zlm;

import io.github.rurulalanana.gb28181.sip.event.ManscdpEvent;
import io.github.rurulalanana.gb28181.sip.manscdp.ManscdpMessage;
import lombok.Getter;
import lombok.ToString;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * 设备侧录像段（标准 9.8 RecordInfo 检索结果）。
 *
 * <p>典型应答结构：
 * <pre>{@code
 * <Response>
 *   <RecordList Num="1">
 *     <Item>
 *       <DeviceID>34020000001320000001</DeviceID>
 *       <Name>...</Name>
 *       <FilePath>/media/record/2026/10/12/ch01_0001.mp4</FilePath>
 *       <Address>...</Address>
 *       <StartTime>2026-10-12T10:00:00</StartTime>
 *       <EndTime>2026-10-12T11:00:00</EndTime>
 *       <Type>time</Type>
 *       <FileSize>102400</FileSize>
 *     </Item>
 *   </RecordList>
 * </Response>
 * }</pre>
 */
@Getter
@ToString
public class RecordItem {

    /** 设备/通道编码。 */
    private final String deviceId;
    /** 录像文件名。 */
    private final String name;
    /** 设备侧文件路径——回放 INVITE 的 {@code u=} 行内容。 */
    private final String filePath;
    /** 存放位置描述（国标字段，实际可用性取决于设备）。 */
    private final String address;
    /** 录像起始时间。 */
    private final Date startTime;
    /** 录像结束时间。 */
    private final Date endTime;
    /** 录像类型：{@code time}（按时间）/ {@code manual}（手动）/ {@code alarm}（告警）。 */
    private final String type;
    /** 文件大小（字节），设备未报时为 {@code 0}。 */
    private final long fileSize;

    RecordItem(String deviceId, String name, String filePath, String address,
               Date startTime, Date endTime, String type, long fileSize) {
        this.deviceId = deviceId;
        this.name = name;
        this.filePath = filePath;
        this.address = address;
        this.startTime = startTime;
        this.endTime = endTime;
        this.type = type;
        this.fileSize = fileSize;
    }

    /** 从 {@code <Item>} 元素解析。 */
    static RecordItem from(Element item) {
        return new RecordItem(
                text(item, "DeviceID"),
                text(item, "Name"),
                text(item, "FilePath"),
                text(item, "Address"),
                parseTime(text(item, "StartTime")),
                parseTime(text(item, "EndTime")),
                text(item, "Type"),
                parseLong(text(item, "FileSize")));
    }

    /**
     * 从 RecordInfo 应答事件中解析全部录像段。
     *
     * @param event MANSCDP 应答事件（{@code onManscdpResponse}）
     * @return 录像段列表；无 RecordList 时返回空列表
     */
    public static List<RecordItem> parse(ManscdpEvent event) {
        List<RecordItem> items = new ArrayList<>();
        if (event == null || event.getManscdp() == null) {
            return items;
        }
        ManscdpMessage msg = event.getManscdp();
        List<Element> lists = msg.getChildren().get("RecordList");
        if (lists == null) {
            return items;
        }
        for (Element list : lists) {
            // 直接遍历 RecordList 的子节点，避免与文档中其他同名列表混淆
            NodeList children = list.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node instanceof Element && "Item".equals(node.getNodeName())) {
                    items.add(from((Element) node));
                }
            }
        }
        return items;
    }

    /** 国标时间格式 {@code yyyy-MM-dd'T'HH:mm:ss}（兼容毫秒与时区后缀，按设备本地时间解释）。 */
    public static Date parseTime(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String value = text.trim();
        String normalized = value;
        if (normalized.endsWith("Z")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        boolean hasTime = normalized.indexOf('T') > 0;
        String pattern;
        if (!hasTime) {
            pattern = "yyyy-MM-dd";
        } else if (normalized.length() > 19 && normalized.charAt(19) == '.') {
            pattern = normalized.endsWith("+08:00") || normalized.contains("+")
                    ? "yyyy-MM-dd'T'HH:mm:ss.SSSXXX" : "yyyy-MM-dd'T'HH:mm:ss.SSS";
        } else if (normalized.length() > 19) {
            pattern = "yyyy-MM-dd'T'HH:mm:ssXXX";
        } else {
            pattern = "yyyy-MM-dd'T'HH:mm:ss";
        }
        try {
            SimpleDateFormat fmt = new SimpleDateFormat(pattern);
            fmt.setLenient(false);
            fmt.setTimeZone(TimeZone.getTimeZone("GMT+8"));
            return fmt.parse(normalized);
        } catch (Exception e) {
            return null;
        }
    }

    /** 供 SDP {@code t=} 行使用的 ISO 8601 文本。 */
    public static String formatTime(Date date) {
        if (date == null) {
            return null;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        fmt.setTimeZone(TimeZone.getTimeZone("GMT+8"));
        return fmt.format(date);
    }

    private static String text(Element item, String tag) {
        NodeList nodes = item.getElementsByTagName(tag);
        if (nodes == null || nodes.getLength() == 0) {
            return null;
        }
        String value = nodes.item(0).getTextContent();
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static long parseLong(String text) {
        try {
            return text == null ? 0L : Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}