package info.nightscout.pump.danars.util;

import android.util.Log;

public class CommandUtil {
   private final static String dataLength = "1400";
   public final static String commandRead = "A3";
   public final static String commandWrite = "A1";

   public static  byte[] generateReadcommand(String headData,String dataId,String devcieNumber){
      StringBuilder sb = new StringBuilder();
      sb.append(headData);
      sb.append(dataLength);
      sb.append(commandRead);
      sb.append(dataId);
      sb.append(StringUtil.stringToHexString(devcieNumber));
      byte[] datas = StringUtil.hexStringToBytes(sb.toString());
      short crc = CRCUtil.getCRC(datas,datas.length);
      byte[] commandBytes = arraycopyByteData(datas,short2Bytes_LH(crc));
      return commandBytes;
   }
   public static  byte[] generateReadcommand(String dataLength,String headData,String dataId,String devcieNumber){
      StringBuilder sb = new StringBuilder();
      sb.append(headData);
      sb.append(dataLength);
      sb.append(commandRead);
      sb.append(dataId);
      sb.append(StringUtil.stringToHexString(devcieNumber));
      byte[] datas = StringUtil.hexStringToBytes(sb.toString());
      short crc = CRCUtil.getCRC(datas,datas.length);
      byte[] commandBytes = arraycopyByteData(datas,short2Bytes_LH(crc));
      return commandBytes;
   }
   public static  byte[] generateReadcommand(String headData,String dataId,String devcieNumber,int page){
      StringBuilder sb = new StringBuilder();
      sb.append(headData);
      sb.append(dataLength);
      sb.append(commandRead);
      sb.append(dataId+StringUtil.intToHexString(page));
      sb.append(StringUtil.stringToHexString(devcieNumber));
      byte[] datas = StringUtil.hexStringToBytes(sb.toString());
      short crc = CRCUtil.getCRC(datas,datas.length);
      byte[] commandBytes = arraycopyByteData(datas,short2Bytes_LH(crc));
      return commandBytes;
   }
   public static  byte[] generateSetBLousCommand(String headData,String dataId,String devcieNumber,double insulin,int time){
      StringBuilder sb = new StringBuilder();
      sb.append(headData);
      sb.append("1700");
      sb.append(commandWrite);
      sb.append(dataId);
      sb.append(StringUtil.stringToHexString(devcieNumber));
      String blousStr = StringUtil.intToHexString((int)(insulin*40));
      if(blousStr.length()==1){
         blousStr = "0"+blousStr+"00";
      }else if(blousStr.length()==2){
         blousStr = blousStr+"00";
      }else if(blousStr.length()==3){
         blousStr=blousStr.substring(1,3)+"0"+blousStr.substring(0,1);
      }
      Log.e("------------>","blousStr="+blousStr);
      sb.append(blousStr);//设置剂量
      String timeStr =StringUtil.intToHexString(time);
      if(timeStr.length()==1){
         timeStr="0"+timeStr;
      }
      Log.e("------------>","timeStr="+timeStr);

      sb.append(timeStr);//设置提醒时间
      byte[] datas = StringUtil.hexStringToBytes(sb.toString());
      short crc = CRCUtil.getCRC(datas,datas.length);
      byte[] commandBytes = arraycopyByteData(datas,short2Bytes_LH(crc));
      return commandBytes;
   }

   /**
    *
    * @param headData
    * @param dataId
    * @param devcieNumber
    * @return
    */
   public static  byte[] generateSetBlousStopCommand(String headData,String dataId,String devcieNumber){
      StringBuilder sb = new StringBuilder();
      sb.append(headData);
      sb.append("1600");
      sb.append(commandWrite);
      sb.append(dataId);
      sb.append(StringUtil.stringToHexString(devcieNumber));
      sb.append("0000");//
      byte[] datas = StringUtil.hexStringToBytes(sb.toString());
      short crc = CRCUtil.getCRC(datas,datas.length);
      byte[] commandBytes = arraycopyByteData(datas,short2Bytes_LH(crc));
      return commandBytes;
   }
   /**
    * 设置基础率
    * @param headData
    * @param dataId
    * @param devcieNumber
    * @return
    */
   public static  byte[] generateSetBasalRate(String headData, String dataId, String devcieNumber, String values){
      StringBuilder sb = new StringBuilder();
      sb.append(headData);
      sb.append("7400");
      sb.append(commandWrite);
      sb.append(dataId);
      sb.append(StringUtil.stringToHexString(devcieNumber));
      sb.append(values);//
      byte[] datas = StringUtil.hexStringToBytes(sb.toString());
      short crc = CRCUtil.getCRC(datas,datas.length);
      byte[] commandBytes = arraycopyByteData(datas,short2Bytes_LH(crc));
      return commandBytes;
   }

   /**
    * 设置临时基础率 百分比模式
    * @param headData
    * @param dataId
    * @param devcieNumber
    * @return
    */
   public static  byte[] generateSetTempsBasalRate(String headData, String dataId, String devcieNumber, String temporaryBasalRatio,String temporaryBasalDuration){
      StringBuilder sb = new StringBuilder();
      sb.append(headData);
      sb.append("1800");
      sb.append(commandWrite);
      sb.append(dataId);
      sb.append(StringUtil.stringToHexString(devcieNumber));
      sb.append("00");//模式 00 百分比；01 胰岛素输注率
      sb.append(temporaryBasalDuration);
      sb.append(temporaryBasalRatio);
      byte[] datas = StringUtil.hexStringToBytes(sb.toString());
      short crc = CRCUtil.getCRC(datas,datas.length);
      byte[] commandBytes = arraycopyByteData(datas,short2Bytes_LH(crc));
      return commandBytes;
   }
   /**
    * 设置临时基础率 百分比模式
    * @param headData
    * @param dataId
    * @param devcieNumber
    * @return
    */
   public static  byte[] generateSetCancelTempsBasalRate(String headData, String dataId, String devcieNumber){
      StringBuilder sb = new StringBuilder();
      sb.append(headData);
      sb.append("1400");
      sb.append(commandWrite);
      sb.append(dataId);
      sb.append(StringUtil.stringToHexString(devcieNumber));
      byte[] datas = StringUtil.hexStringToBytes(sb.toString());
      short crc = CRCUtil.getCRC(datas,datas.length);
      byte[] commandBytes = arraycopyByteData(datas,short2Bytes_LH(crc));
      return commandBytes;
   }
   //拷贝byte[]，让两个byte[]的数据拼接起来
   public static byte[] arraycopyByteData(byte[] src1,byte[] src2){
      byte[] newBytes = new byte[src1.length+src2.length];
      System.arraycopy(src1, 0, newBytes, 0, src1.length);
      System.arraycopy(src2, 0, newBytes, src1.length, src2.length);
      return newBytes;
   }
   /**
    * 拷贝byte[]，提取byte[]中的部分数据
    * @param src 原始数组
    * @param startPosition 开始拷贝的位置
    * @param length 拷贝的长度
    */
   public static byte[] arrayCopy(byte[] src,int startPosition,int length){
      byte[] newBytes = new byte[length];
      System.arraycopy(src, startPosition, newBytes, 0, length);
      return newBytes;
   }

   public static byte[] short2Bytes_LH(short shortVal) {
      byte[] bytes = new byte[2];
      bytes[0] = (byte) (shortVal & 0xff);
      bytes[1] = (byte) (shortVal >> 8 & 0xff);
      return bytes;
   }
}
