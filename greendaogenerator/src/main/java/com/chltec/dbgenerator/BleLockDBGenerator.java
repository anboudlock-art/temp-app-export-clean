package com.chltec.dbgenerator;

import org.greenrobot.greendao.generator.DaoGenerator;
import org.greenrobot.greendao.generator.Entity;
import org.greenrobot.greendao.generator.Schema;

public class BleLockDBGenerator {

    public static void main(String args[]){
        Schema schema = new Schema(1,"com.chltec.blelock.db.dao");

        //蓝牙锁表
        Entity bleLock = schema.addEntity("BleLock");
        bleLock.addStringProperty("id").primaryKey();               //主键id, string类型
        bleLock.addStringProperty("name");                          //蓝牙锁名称
        bleLock.addStringProperty("password");                      //蓝牙锁连接密码
        bleLock.addStringProperty("key_password");                  //蓝牙锁按键密码
        bleLock.addLongProperty("updated_at");                      //更新时间,同步使用
//        bleLock.addIntProperty("linked");                           //蓝牙锁连接状态
        bleLock.addIntProperty("energy");                           //蓝牙锁电量
        bleLock.addIntProperty("mode");                             //蓝牙锁开锁模式,自动or手动
        bleLock.addIntProperty("status");                           //蓝牙锁开关状态,打开or关闭
        bleLock.addIntProperty("user_id");                          //绑定的用户id，一个手机多个用户情况
        bleLock.addLongProperty("deleted_at");                      //删除时间(解绑时间)

        //蓝牙锁操作记录表90
        Entity bleLockLog = schema.addEntity("BleLockLog");
        bleLockLog.addLongProperty("id").primaryKey().autoincrement();//主键id,long类型（主键自增）
        bleLockLog.addStringProperty("blelock_id");                 //蓝牙锁id,string类型
        bleLockLog.addLongProperty("user_id");                      //用户id
        bleLockLog.addFloatProperty("longitude");                   //开锁地址经度
        bleLockLog.addFloatProperty("latitude");                    //开锁地址纬度
        bleLockLog.addStringProperty("log");                        //开锁记录；开锁or关锁
        bleLockLog.addLongProperty("opened_at");                    //开锁时间
        bleLockLog.addIntProperty("mode");                          //开锁模式


        try {
            new DaoGenerator().generateAll(schema, "blelock/src/main/java");
        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println("blelock green dao generate success!");
    }

}
