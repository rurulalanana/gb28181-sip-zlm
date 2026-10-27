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

    /**
     * 以已知文件路径构造录像项（设备未提供完整检索结果时使用）。
     *
     * @param deviceId 设备/通道编码
     * @param filePath 设备侧文件路径（回放 INVITE 的 {@code u=}）
     */
    public static RecordItem ofFilePath(String deviceId, String filePath) {
        return new RecordItem(deviceId, filePath, filePath, null, null, null, "manual", 0L);
    }

    /**
     * 以<b>时间区间</b>构造录像项（设备未提供 {@code <FilePath>} 时使用）。
     *
     * <p><b>为什么需要它</b>：标准 A.2.4.4 里 {@code FilePath} 是<b>可选</b>字段，
     * 实测 LiveGBS 与大量国产设备根本不填 —— 它们按「时间段」管理录像，
     * 回放时只认 {@code StartTime}/{@code EndTime}。
     * 如果平台强依赖 FilePath，这些设备的录像列表能查到但点播不了，
     * 表现为「有录像，点开报缺少 filePath」。
     *
     * @param deviceId  设备/通道编码
     * @param startTime 录像段起始时间
     * @param endTime   录像段结束时间
     * @param name      录像名称（设备侧文件名，可能为空）
     */
    public static RecordItem ofRange(String deviceId, java.util.Date startTime,
                                     java.util.Date endTime, String name) {
        return new RecordItem(deviceId, name, null, null, startTime, endTime, "time", 0L);
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

    /**
     * 国标时间解析：兼容带 {@code T} 与空格两种写法（毫秒/时区后缀也接受），
     * 一律按设备本地时间（GMT+8）解释。
     *
     * <p><b>为什么两种都要认</b>：标准表 45 要求 {@code yyyy-MM-ddTHH:mm:ss}，
     * 但相当一部分设备（含部分国产 NVR）回的是空格分隔的
     * {@code 2026-10-26 12:00:00}。原实现用 {@code indexOf('T')} 判断有没有时间部分，
     * 空格形式会被当成<b>纯日期</b>套 {@code yyyy-MM-dd} 解析，且
     * {@code setLenient(false)} 让它直接失败返回 {@code null} ——
     * 于是整条录像被丢弃（{@code cacheDeviceRecords} 会跳过 startTime 为空的项），
     * 界面上表现为「设备明明回了录像但一段都看不到」。
     */
    public static Date parseTime(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String normalized = text.trim();
        if (normalized.endsWith("Z")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        // 空格写法先归一成标准写法，后续逻辑只需处理一种形态
        int space = normalized.indexOf(' ');
        if (normalized.indexOf('T') < 0 && space > 0) {
            normalized = normalized.substring(0, space) + "T"
                    + normalized.substring(space + 1).trim();
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

    /**
     * 时间的 <b>UNIX 秒</b>表示，用于 SDP 的 {@code t=} 行。
     *
     * <p><b>{@code t=} 必须是整数，不能写 ISO 时间字符串</b>。
     * RFC 4566 规定 {@code t=} 是 NTP 时间戳（自 1900 年的秒数），
     * 国标设备与平台普遍按「1970 起的秒数」解析（WVP-GB28181-pro 同样用
     * {@code Instant.getEpochSecond()}）。
     * 实测写 {@code t=2026-10-20T10:00:00 2026-10-20T12:00:00} 会被
     * LiveGBS 直接拒绝：
     * <pre>
     * SIP/2.0 400 parse invite sdp error, sdp: strconv.ParseInt:
     * parsing "2026-10-20T10:00:00": invalid syntax on line 6
     * </pre>
     *
     * @return UNIX 秒；{@code date} 为 {@code null} 时返回 0
     */
    public static long epochSecond(Date date) {
        return date == null ? 0L : date.getTime() / 1000L;
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