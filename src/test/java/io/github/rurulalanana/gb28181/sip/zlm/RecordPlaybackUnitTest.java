package io.github.rurulalanana.gb28181.sip.zlm;

import io.github.rurulalanana.gb28181.sip.event.ManscdpEvent;
import io.github.rurulalanana.gb28181.sip.manscdp.ManscdpMessage;
import io.github.rurulalanana.gb28181.sip.manscdp.ManscdpXml;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 录像段解析与回放 SDP 构造的单元测试（纯逻辑，不依赖原生库）。
 */
public class RecordPlaybackUnitTest {

    private static final String CAMERA_ID = "34020000001320000001";

    private static final String RECORD_INFO_XML =
            "<?xml version=\"1.0\"?>\n"
                    + "<Response>\n"
                    + "  <CmdType>RecordInfo</CmdType>\n"
                    + "  <SN>10</SN>\n"
                    + "  <DeviceID>" + CAMERA_ID + "</DeviceID>\n"
                    + "  <SumNum>2</SumNum>\n"
                    + "  <RecordList Num=\"2\">\n"
                    + "    <Item>\n"
                    + "      <DeviceID>" + CAMERA_ID + "</DeviceID>\n"
                    + "      <Name>通道1 录像</Name>\n"
                    + "      <FilePath>/media/record/ch01_0001.mp4</FilePath>\n"
                    + "      <Address>园区东门</Address>\n"
                    + "      <StartTime>2026-10-12T10:00:00</StartTime>\n"
                    + "      <EndTime>2026-10-12T11:00:00</EndTime>\n"
                    + "      <Type>time</Type>\n"
                    + "      <FileSize>104857600</FileSize>\n"
                    + "    </Item>\n"
                    + "    <Item>\n"
                    + "      <DeviceID>" + CAMERA_ID + "</DeviceID>\n"
                    + "      <Name>告警录像</Name>\n"
                    + "      <FilePath>/media/record/ch01_0002.mp4</FilePath>\n"
                    + "      <StartTime>2026-10-12T12:00:00</StartTime>\n"
                    + "      <EndTime>2026-10-12T12:30:00</EndTime>\n"
                    + "      <Type>alarm</Type>\n"
                    + "    </Item>\n"
                    + "  </RecordList>\n"
                    + "</Response>";

    @Test
    public void 应解析出全部录像段() {
        List<RecordItem> items = RecordItem.parse(eventOf(RECORD_INFO_XML));
        assertEquals("应解析出 2 段录像", 2, items.size());

        RecordItem first = items.get(0);
        assertEquals(CAMERA_ID, first.getDeviceId());
        assertEquals("/media/record/ch01_0001.mp4", first.getFilePath());
        assertEquals("time", first.getType());
        assertEquals(104857600L, first.getFileSize());
        assertNotNull(first.getStartTime());
        assertNotNull(first.getEndTime());
        assertTrue("录像时长应为 1 小时", (first.getEndTime().getTime() - first.getStartTime().getTime()) == 3600_000L);

        RecordItem second = items.get(1);
        assertEquals("alarm", second.getType());
        assertEquals("缺省 FileSize 应为 0", 0L, second.getFileSize());
        assertNull(second.getAddress());
    }

    @Test
    public void 无RecordList时返回空列表() {
        String xml = "<Response><CmdType>RecordInfo</CmdType><SN>1</SN>"
                + "<DeviceID>" + CAMERA_ID + "</DeviceID><SumNum>0</SumNum></Response>";
        assertTrue(RecordItem.parse(eventOf(xml)).isEmpty());
    }

    @Test
    public void 回放SDP应含历史位SSRC与时间段() {
        RecordItem item = RecordItem.parse(eventOf(RECORD_INFO_XML)).get(0);
        Gb28181MediaService service = newService();
        RtpSession session = new RtpSession(CAMERA_ID, CAMERA_ID, "1200000001", null, 0,
                "call-1", null, 6000, MediaType.PLAYBACK, item.getFilePath());

        String sdp = service.buildPlaybackSdp(session, item).toText();
        assertTrue("应为回放: " + sdp, sdp.contains("s=Playback"));
        assertTrue("应带 u= 文件路径: " + sdp, sdp.contains("u=/media/record/ch01_0001.mp4"));
        assertTrue("应带录像时间段: " + sdp,
                sdp.contains("t=2026-10-12T10:00:00 2026-10-12T11:00:00"));
        assertTrue("应为 PS/90000: " + sdp, sdp.contains("a=rtpmap:96 PS/90000"));
        assertTrue("SSRC 首位应为历史位 1: " + sdp, sdp.contains("y=1200000001"));
        assertTrue("应带文件大小: " + sdp, sdp.contains("a=filesize:104857600"));
    }

    @Test
    public void 会话类型映射() {
        assertEquals(MediaType.REALTIME, MediaType.ofSessionName("Play"));
        assertEquals(MediaType.PLAYBACK, MediaType.ofSessionName("Playback"));
        assertEquals(MediaType.DOWNLOAD, MediaType.ofSessionName("Download"));
        assertEquals(MediaType.REALTIME, MediaType.ofSessionName("未知"));
        assertTrue(MediaType.PLAYBACK.historical());
        assertTrue(MediaType.DOWNLOAD.historical());
        assertTrue(!MediaType.REALTIME.historical());
    }

    @Test
    public void 下载会话SDP应携带u行() {
        Gb28181MediaService service = newService();
        RtpSession session = new RtpSession(CAMERA_ID, CAMERA_ID, "1200000002", null, 0,
                "call-2", null, 6001, MediaType.DOWNLOAD, "/media/record/ch01_0002.mp4");
        String sdp = service.buildAnswerSdp(session, false).toText();
        assertTrue(sdp.contains("s=Download"));
        assertTrue(sdp.contains("u=/media/record/ch01_0002.mp4"));
        assertTrue("下载 SSRC 也取历史位: " + sdp, sdp.contains("y=1200000002"));
    }

    private Gb28181MediaService newService() {
        MediaServerProperties props = MediaServerProperties.builder()
                .platformId("34020000002000000001")
                .mediaIp("192.168.1.100")
                .build();
        ZlmMediaServer mediaServer = new ZlmMediaServer(props);
        return new Gb28181MediaService(mediaServer, new RtpSessionManager(mediaServer, props), props);
    }

    private ManscdpEvent eventOf(String xml) {
        ManscdpMessage msg = ManscdpXml.parse(xml);
        return ManscdpEvent.builder().manscdp(msg).xml(xml).build();
    }
}