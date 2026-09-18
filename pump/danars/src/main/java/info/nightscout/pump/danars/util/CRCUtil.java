package info.nightscout.pump.danars.util;

import java.util.Locale;

public class CRCUtil {
   public static short getCRC(byte[] pSendBuf,int nEnd){
      int i;
      int wCrc = 0xFFFF;
      for(i = 0;i<nEnd;i++){
         wCrc^=(pSendBuf[i]&0xFF);
         for(short j=0;j<8;j++){
            if((wCrc&1)!=0){
               wCrc>>=1;
               wCrc^=0xA001;
            }else{
               wCrc>>=1;
            }
         }
      }
      return (short)wCrc;
   }
   public static String generateCRC(byte[] pSendBuf,int nEnd){
      short crcVlaue = getCRC(pSendBuf,nEnd);
      //讲CRC转华为大写的16进制字符串
      return String.format("%4x",crcVlaue&0xFFFF).toUpperCase(Locale.ROOT);
   }

   //检验crc是否正确
   public static boolean isCrcCorrect(byte[] datas){
      //通过CRC校验，看数据是否传输完成
      byte[] dataWihoutCRC = new byte[datas.length-2];
      byte[] crc = new byte[2];
      //拷贝数组到dataWihoutCRC
      System.arraycopy(datas, 0, dataWihoutCRC, 0, datas.length-2);
      //拷贝数组到CRC
      System.arraycopy(datas, dataWihoutCRC.length, crc, 0, 2);
      String crcGenerate = CRCUtil.generateCRC(dataWihoutCRC,dataWihoutCRC.length);//使用数据段 生成的CRC码
      String crcStr = StringUtil.bytesToHexString(crc);//数据中返回的CRC码(此CRC码是高低位切换后的字符串)
      String crcFromData = crcStr.substring(2,4)+crcStr.substring(0,2) ;//还原高低位顺序
      if(crcGenerate.equals(crcFromData)){
         return true;
      }else{
         return false;
      }
   }
}
