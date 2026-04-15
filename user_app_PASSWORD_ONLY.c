#include "DebugLog.h"
#include <string.h>
#include "lib.h"
#include "gpio.h"
#include "spi.h"
#include "flash.h"
#include "ABD_ble_lock_service.h"
#include "softtimer.h"
#include "timer.h"
#include "wdt.h"
#include "delay.h"

#include "uart.h"
#include "user_app.h"
#include "os_timer.h"

#include "led.h"
#include "battery.h"
#include "input.h"
#include "motor.h"
#include "my_aes.h"

#include "e103w08b.h"
#include "Uart_4G.h"
#include "user.h"
#include "cpwm.h"


#define USER_FLASH_BASE_ADDR 0	//flash data 8K
#define USER_FLASH_LENGHT 8

#define BATTERY_LOW_LEVEL		20	//低电量 30%  2-3V：0-100% 即2.3V

//notify打开标志
uint8_t start_tx=0;	//允许notify标志

uint8_t gatt_buff[20]={0};		//接收的数据

// 连接id
uint8_t connect_flag=0;	//连接成功标志

uint8_t WakeupSource=NONE;	//唤醒源标志

uint8_t Systerm_States=SLEEP;

uint8_t EnterSleepFlag=0;

//uint8_t CloseCMDReceived=0;

/***********************
 * TYPEDEFS (类型定义)
 **********************/
#define	 DEFAULT_CODE	0xA5

#define  NONE_KEY		0x00
#define  SHORT_KEY	0x01
#define  LONG_KEY		0x02

#define  KEY_DOWN		0
#define  KEY_UP     1

/*******************************
 * GLOBAL VARIABLES (全局变量)
 ******************************/
 
//系统密码等参数(存储在flash中)
static SystemParameter_Def SystemParameter;

uint8_t lock_state = 0;

//密码验证成功标志

static uint8_t   cmd_id=0;

uint8_t GetConnectTimeSuccess = 0;

uint8_t ConnectPasswordCorrect = 0;

// 密码设置标志位
//uint8_t PasscodeSetFlag = 0;

static uint8_t ADV_Flag = 0;

/******************************
 * LOCAL VARIABLES (本地变量)
 *****************************/
static uint8_t   BatValue;         		//电量

//AES加解密
uint8_t aes_key1[16];
uint8_t aes_key2[16];

unsigned char key_state=KEY_UP;
unsigned char key_lock = 0;

unsigned char key_enable=0;				//按键使能标志
unsigned int key_delay_cnt = 0;
unsigned char key_code = NONE_KEY;				//

unsigned char StateKeyState=KEY_UP;

uint8_t key_state_lock=0;
uint8_t LockOpen_Report = 0;//0 收到的APP是关锁  1 收到的APP是开锁

u8 timerSendFlag=0;

extern void rf_stop(void);
extern void rf_restart(void);

void  GPIO_callback(void);

//void aes_test(void)
//{
//	uint8_t i;
//	uint8_t key[16]={ 0x64, 0x69, 0x4E, 0x86, 0x1A, 0xE8, 0x11, 0x22, 0x33,0x44,0x55, 0x66,0x77, 0x88, 0x99, 0xaa};
//	uint8_t data[16]={0xFB, 0x04, 0xAA, 0x1F, 0x10, 0x2F, 0xFC, 0xFC, 0xFC, 0xFC, 0xFC, 0xFC, 0xFC, 0xFC, 0xFC, 0xFC};
//	uint8_t buff[16];
//	
//	uint8_t key1[16];
//	uint8_t buff1[16];
//	
//	for(i=0;i<16;i++)
//	{
//		key1[i] = key[i];
//	}
//	
//	for(i=0;i<16;i++)
//	{
//		buff1[i] = data[i];
//	}
//	
//	//smp_aes_encrypt(key1, buff1, buff);
//	
//	aes_encrypt_ecb(key1,16,  buff1, buff, 1);
//	
//	DBGHEXDUMP("encrypt_0:\r\n",buff,16);
//	
//	for(i=0;i<16;i++)
//	{
//		key1[i] = key[15-i];
//	}
//	
//	for(i=0;i<16;i++)
//	{
//		buff1[i] = data[i];
//	}
//	
//	//smp_aes_encrypt(key1, buff1, buff);
//	
//	aes_encrypt_ecb(key1,16,  buff1, buff, 1);
//	
//	DBGHEXDUMP("encrypt_1:\r\n",buff,16);
//	
//	for(i=0;i<16;i++)
//	{
//		key1[i] = key[i];
//	}
//	
//	for(i=0;i<16;i++)
//	{
//		buff1[i] = data[15-i];
//	}
//	
//	//smp_aes_encrypt(key1, buff1, buff);
//	
//	aes_encrypt_ecb(key1,16,  buff1, buff, 1);
//	
//	DBGHEXDUMP("encrypt_2:\r\n",buff,16);
//	
//	for(i=0;i<16;i++)
//	{
//		key1[i] = key[15-i];
//	}
//	
//	for(i=0;i<16;i++)
//	{
//		buff1[i] = data[15-i];
//	}
//	
//	//smp_aes_encrypt(key1, buff1, buff);
//	
//	aes_encrypt_ecb(key1,16,  buff1, buff, 1);
//	
//	DBGHEXDUMP("encrypt_3:\r\n",buff,16);
//}


/**********************************************************
函数名: time1000ms_prc
描  述: 1s处理一次
输入值: 无
输出值: 无
返回值: 无
备  注: 无compare
**********************************************************/
void time1000ms_prc(void){
	if(net4G.BC95_status == BC95_sendmessage){
		if(net4G.lost_cnt){
			net4G.lost_cnt--;
			if(net4G.lost_cnt==0){
				net4G.BC95_status = BC95_offline;
				net4G.BC95_step = 0;
				dbg_printf("4G_OffLine");
				//flg_poweron = 0;
				_user.powerondelay1s = 200/2;
				GPIO_Pin_Clear(U32BIT(PWR_4G));//S4G_PWR_OFF();//复位				
			}
		}

		if(net4G.senddelaytime) net4G.senddelaytime--;
		else{
			net4G.senddelaytime = net4G.senddelaytime_set;

			flg_cmd_gpsdata = 1;

		} 
	}

}



void auto_close(void){

	
	_user.bat_volt = BatValue = GetBatCapacity();
	

	//电池电量充足才会启动马达
	if(BatValue >= BATTERY_LOW_LEVEL)
	{
		//OS_timer_start(EVT_MOTOR_STOP, 500, false);	//5S 后自动停止			
		// if(0==key_state_lock)		
		// {
			if(1==GetInputStopR())		//如果是开锁的状态
			{
				Systerm_States |= AUTOLOCK;
				// #if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
				// dbg_printf("Wakeup from autolock\r\n");
				// #endif
				OS_timer_start(EVT_AUTO_LOCK, 0, false);	//OS_timer_start(EVT_AUTO_LOCK, 2, false);				//20mS 后自动停止
				key_state_lock = 1;//关锁状态
			}
			// else 
			// {
			// 	OS_timer_start(EVT_MOTOR_STOP, 2, false);	//20mS 后自动停止
			// }
		// }
		//OS_timer_start(EVT_ENTER_SLEEP, 2100, false);		//21s后进入休眠
	}
	// else		//低电量
	// {
	// 	RedLed_Config(RED_FAST_FLASH_1);
	// 	OS_timer_SetEvt(EVT_REDLED_CONTROL);
		
	// 	OS_timer_start(EVT_ENTER_SLEEP, 600, false);	//6s后进入休眠
	// }
}
/**********************************************************
函数名: bt_slot20ms
描  述:
输入值: 无
输出值: 无
返回值: 无
备  注: 无
**********************************************************/
void bt_slot10ms(void){
	static u8 R_500mscnt,R_200mscnt;

	if(_user.key_7delay) _user.key_7delay--;

	if(++R_200mscnt>=20){
		R_200mscnt = 0;
		flg_200ms ^= 1;
	}

	R_500mscnt++;
	
	if(R_500mscnt>=50){
		R_500mscnt = 0;

		flg_ledflash ^= 1;
		if(flg_ledflash==0){
			time1000ms_prc();
		}
	}


}

/**********************************************************
函数名: updatedisp
描  述:
输入值: 无
输出值: 无
返回值: 无
备  注: 无
**********************************************************/
void updatedisp(void){
	if(flg_4g_EN==1){
		if(net4G.BC95_status == BC95_sendmessage){
			// if(flg_ledflash){
				greenLedOn();
			// }
			// else{
				// greenLedOff();
			// }
		}
		else{
			if(flg_200ms){
				greenLedOn();
			}
			else{
				greenLedOff();
			}
		}
	}
	else{
		greenLedOff();
	}

}
/**********************************************************
函数名: TIM2_INT_Callback
描  述:
输入值: 无
输出值: 无
返回值: 无
备  注: 无
**********************************************************/
static void Timer_2_callback(void)
{
	static u8 bt_slotcnt;
	hall_check();//剪断报警方检测
	if(flg_4g_EN){
		net4G_prc();
	}

	switch(++bt_slotcnt){
		case 1:
	
			os_timer_query();	
		break;
		case 2:
			if(_user.lock_status != lock_state){
				if(net4G.BC95_status == BC95_sendmessage){
					flg_cmd_gpsdata = 1;
				}
				_user.lock_status = lock_state;//同步变量
			}
		break;
		case 3:
			// key_scan();
			// dealkey();			
		break;
		case 4:
			updatedisp();
		break;
		case 5:
			bt_slot10ms();
			bt_slotcnt = 0;
			flg_10ms = 1;
			if(key_state==KEY_DOWN)
				key_delay_cnt++;

		break;
	}	


}

void Enable_Timer2_2ms(void)
{
	timer_2_enable(32768*2/1000, Timer_2_callback);
}

void Disable_Timer2_2ms(void)
{
	timer_2_disable();
}


/*********************************************************************************
*函数名称:computer_bcc
*功能描述:计算BCC校验和
*入口参数:@data,校验数据的起始地址   @len,校验数据的长度
*返回值:输入数据的BCC校验和
**********************************************************************************/
//static uint8_t computer_bcc(uint8_t *data,uint16_t lenth)
//{
//  uint8_t bcc = 0;
//  uint16_t i = 0;
//  for(i = 0;i < lenth;i ++)
//  {
//    bcc ^= data[i];
//  }
//  return bcc;
//}

/*********************************************************************************
*函数名称:computer_sum
*功能描述:计算校验和
*入口参数:@data,校验数据的起始地址   @len,校验数据的长度
*返回值:输入数据的BCC校验和
**********************************************************************************/
static uint8_t computer_sum(uint8_t *data,uint16_t lenth)
{
  uint8_t bcc = 0;
  uint16_t i = 0;
  for(i = 0;i < lenth;i ++)
  {
    bcc += data[i];
  }
  return bcc;
}

/* 更新参数 */
void UpdateFlashParameter(void)
{
	uint8_t tmp[8]={0}; 
	
	__disable_irq();
	
	EraseFlashData(USER_FLASH_BASE_ADDR, 1);			//擦除扇区 4K字节
	
	tmp[0] = SystemParameter.default_id;		
	tmp[1] = SystemParameter.password[0];
	tmp[2] = SystemParameter.password[1];
	tmp[3] = SystemParameter.password[2];
	tmp[4] = SystemParameter.lockstate;
	tmp[5] = SystemParameter.discharge_flag;
	
	WriteFlashData(USER_FLASH_BASE_ADDR, USER_FLASH_LENGHT, tmp);
	
	__enable_irq();
	
}


/* 上电获取之前参数 */
void ConfigParameterDefault(void)
{
	uint8_t tmp[8]={0}; 
	
	//spi_init(FLASH_SPI);

	
	//读取flash数据到缓存区
	ReadFlashData(USER_FLASH_BASE_ADDR, USER_FLASH_LENGHT, tmp);
	
	//我们利用flash中一个字节是否=0x5A作为第一次烧录后的运行， 从而设置参数的出厂设置
	if(tmp[0] != DEFAULT_CODE)	//如果是第一次烧写运行
	{
		EraseFlashData(USER_FLASH_BASE_ADDR, 1);			//擦除扇区 4K字节
		SetAllParaDefault();
		tmp[0] = SystemParameter.default_id = DEFAULT_CODE;		
		tmp[1] = SystemParameter.password[0];
		tmp[2] = SystemParameter.password[1];
		tmp[3] = SystemParameter.password[2];
		tmp[4] = SystemParameter.lockstate;
		tmp[5] = SystemParameter.discharge_flag;
		WriteFlashData(USER_FLASH_BASE_ADDR, USER_FLASH_LENGHT, tmp);           
	} 
	else		//不是第一次烧写运行
	{
		SystemParameter.default_id = tmp[0];
		SystemParameter.password[0] = tmp[1];
		SystemParameter.password[1] = tmp[2];
		SystemParameter.password[2] = tmp[3];
		SystemParameter.lockstate 	= tmp[4];
		SystemParameter.discharge_flag = tmp[5];
	}
	//SystemParameter.lockstate = GetDoorState();
	dbg_printf("CODE:0x%02x\r\n", tmp[0]);
	dbg_printf("password0:0x%02x\r\n", tmp[1]);
	dbg_printf("password1:0x%02x\r\n", tmp[2]);
	dbg_printf("password2:0x%02x\r\n", tmp[3]);
	
	dbg_printf("state:0x%02x\r\n", tmp[4]);
	dbg_printf("dscg:0x%02x\r\n", tmp[5]);
}

/* 全部参数恢复出厂设置 */
void SetAllParaDefault(void)    
{     
  //密码范围0-999999(对应16进制0x000000-0x0f423f)
  uint32_t passwordcode = 123456;
	
  passwordcode %= 1000000;
  SystemParameter.password[0] = (uint8_t)(passwordcode>>16);                      
  SystemParameter.password[1] = (uint8_t)(passwordcode>>8);
  SystemParameter.password[2] = (uint8_t)passwordcode; 
	
	//锁状态 关闭
	SystemParameter.lockstate = 0;
	
	//保留参数
	SystemParameter.discharge_flag = 71;
}


/*********************************************************************
 * @fn      slp_gatt_report_notify
 *
 * @brief   Send HID notification, keys, mouse values, etc.
 *
 *
 * @param   rpt_info_id - report idx of the hid_rpt_info array.
 *          len         - length of the HID information data.
 *          p_data      - data of the HID information to be sent.
 *
 * @return  none.
 */

/* 发送给手机端 */
void user_encrypt_notify(const unsigned char* Key, uint8_t *rawDataBuf, uint16_t rawDatalength)
{
	
	struct gap_att_report report;
	uint8_t send_buff[16];
	uint8_t rawdata[16];
	
	uint8_t key[16]={ 0x64, 0x69, 0x4E, 0x86, 0x1A, 0xE8, 0x11, 0x22, 0x33,0x44,0x55, 0x66,0x77, 0x88, 0x99, 0xaa};
	uint8_t data[16]={0xFB, 0x04, 0xAA, 0x1F, 0x10, 0x2F, 0xFC, 0xFC, 0xFC, 0xFC, 0xFC, 0xFC, 0xFC, 0xFC, 0xFC, 0xFC};
	uint8_t buff[16];
	
	memset(send_buff, 0, 16);
		
	if(rawDatalength<14)
	{
		rawdata[0] = 0xFB;
		rawdata[1] = rawDatalength;
		memcpy(rawdata+2,rawDataBuf,rawDatalength);
		memset(rawdata+2+rawDatalength, 0xFC, 16-2-rawDatalength);
		
		#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
		DBGHEXDUMP("encrypt data:\r\n",rawdata,16);
		
		//开始加密		
		DBGHEXDUMP("encrypt key:\r\n",(uint8_t *)Key,16);
		#endif
		
		//__disable_irq();
		aes_encrypt_ecb(Key, 16, rawdata, send_buff, 1);
		//__enable_irq();
		
		#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
		DBGHEXDUMP("encrypted:\r\n",send_buff,16);
		#endif
		
		if(start_tx)		//如果notify打开
		{			
			#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
			DBGHEXDUMP("gatt_notify:\r\n",send_buff,16);
			#endif
			
			report.primary = BLE_VendorV2;
			report.uuid = BLE_VendorV2_Notify_UUID;
			report.hdl = BLE_VendorV2_Notify_VALUE_HANDLE;
			report.value = BLE_GATT_NOTIFICATION;

			GATTDataSend(BLE_GATT_NOTIFICATION, &report, 16, send_buff);
		}
	}
}


/* 蓝牙建立连接后 */
void gap_connect_evnet(void)
{
	OS_timer_stop(EVT_ENTER_SLEEP);
	RedLed_Config(RED_LED_OFF);
	OS_timer_SetEvt(EVT_REDLED_CONTROL);
	GetConnectTimeSuccess = 0; 			//
	ConnectPasswordCorrect = 0;
	OS_timer_start(EVT_ENTER_SLEEP, 3000, 0);	//30s 后自动断开连接
}


/* 蓝牙断开连接后 */
void gap_disconnect_evnet(void)
{
	start_tx = 0;
	if(Systerm_States == OTAMODE)
	{
		delay_ms(100);
		SystemReset();
		delay_ms(1000);
	}
	else
	{
		if(flg_4g_EN==0){//4G未开则直接1S进休眠
			OS_timer_stop(EVT_ENTER_SLEEP);
			OS_timer_start(EVT_ENTER_SLEEP, 100, 0);	//1s 后自动进入休眠
		}
	}	
}

void ota_connect_evnet(void)
{
	if(Systerm_States != OTAMODE)
	{
		wdt_disable();
		
		//OS_timer_stop(EVT_FEEDWTD_ADV);
		
		OS_timer_stop(EVT_ENTER_SLEEP);
		
		AllLedOff();
		
		Disable_Timer2_2ms();
		#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
			dbg_printf("OTA Mode\r\n");
		#endif
	}
}

/* 与手机端密码验证正确后 */

/* 关闭其他唤醒源,只保留wdt */
void WakupConfig_Disable(void)
{
	struct gap_wakeup_config pw_cfg;
	
	io_irq_disable_all();
	
	GPIO_Set_Input(U32BIT(STATE_KEY)|U32BIT(STOP_KEY1)|U32BIT(STOP_KEY2)|U32BIT(GPIO_USER_KEY)|U32BIT(lock_hall),0);
	PIN_Pullup_Enable(T_QFN_48, U32BIT(STATE_KEY)|U32BIT(STOP_KEY1)|U32BIT(STOP_KEY2)|U32BIT(GPIO_USER_KEY));
	
	//delay_ms(2);
	
	pw_cfg.wakeup_type = SLEEP_WAKEUP;
	pw_cfg.wdt_wakeup_en = (bool)false;
	pw_cfg.rtc_wakeup_en = (bool)true;
	pw_cfg.timer_wakeup_en = (bool)false;
	pw_cfg.gpi_wakeup_en = (bool)false;
	pw_cfg.gpi_wakeup_cfg = 0;					//中断唤醒pin
	WakeupConfig(&pw_cfg);
	
	//io_irq_enable(U32BIT(STATE_KEY), GPIO_callback);	//使能按键中断
}


/* 硬件初始化 */
void hardware_init(void)
{
	LedBlink();
	
	LedInit();
	
	MotorInit();
	
	lock_state = GetDoorState();
	ConfigParameterDefault();
	
 if(lock_state==0){flg_do_closelockOK = 1;} //250916+ 
}

/*****************************************************************************
用户秘匙初始化
思路：
EncryptAndDecrypKey1： 作为 APP 与设备连接成功后发送时间指令的秘钥。
EncryptAndDecrypKey2 ： 作 为 接 收 到 时 间 指 令 后 正 常 秘 钥 使 用 （ 接 收 到 当 前 时 间 后 替 换
EncryptAndDecrypKey2 的最后 3 个字节） 。
******************************************************************************/
void UserEncryptInit(void)
{
	uint8_t i;
	struct gap_ble_addr dev_addr;

	dev_addr.type = PUBLIC_ADDRESS_TYPE;
	dev_addr.addr[5]=0x64;
	dev_addr.addr[4]=0x69;
	dev_addr.addr[3]=0x4E;
	dev_addr.addr[2]=0x86;
	dev_addr.addr[1]=0x1A;
	dev_addr.addr[0]=0xE8;
	SetDevAddr(&dev_addr);
	
	GetDevAddr(&dev_addr);
	#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)	
		DBGHEXDUMP("MAC:\r\n",dev_addr.addr,6);
	#endif
	
	
	//秘钥 1 和 2 初始化
	for(i=0;i<6;i++)
	{
		aes_key1[i] = dev_addr.addr[5-i];
		aes_key2[i] = dev_addr.addr[5-i];
	}
	
	aes_key1[6] = 0x11;
	aes_key2[6] = 0x11;
	
	for(i=0;i<9;i++)
	{
		aes_key1[i+7] = aes_key1[i+6] + 0x11;
		aes_key2[i+7] = aes_key1[i+6] + 0x11;
	}
	
	#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
	DBGHEXDUMP("Key1:\r\n",aes_key1,16);
	DBGHEXDUMP("Key2:\r\n",aes_key1,16);
	#endif
	
}

void user_wakeup(void)
{
	if(WakeupSource>0)	//有唤醒事件产生
	{		

		//唤醒后都需要执行的事件
		WakupConfig_Disable();		//禁止唤醒源

		lock_state = GetDoorState();

		_user.key_7cnt = 0;
		_user.key_7delay = 0;

		#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
		dbg_printf("State:0x%02x\r\n", lock_state);	
		#endif
		
		RTC_EVT_Stop(RTCEVT_72h);		 //其他情况停止事件	
		RTC_EVT_Stop(RTCEVT_24h);		 //其他情况停止事件					
		_user.bat_volt = BatValue = GetBatCapacity(); //battery capacity init
		#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
		dbg_printf("battery:%d%%\r\n", BatValue);
		#endif
		
		wdt_enable(128*10);//128*5				//10s以内喂狗即可 256/32.768 = 7.8ms 				
		wdt_clear();
		//OS_timer_start(EVT_FEEDWTD_ADV, 100, 1);	//定时1s 喂狗
		
		Enable_Timer2_2ms();			//启动用户10ms定时
		
		//key_enable = 1;		//按键检测使能
		key_state = KEY_UP;
		key_delay_cnt = 0;
		key_lock = 0;
		//GPIO_Set_Input(U32BIT(GPIO_USER_KEY),0);
		//PIN_Pullup_Enable(T_QFN_48, U32BIT(GPIO_USER_KEY));		//设置为输入模式
		//delay_ms(10);
		
		if(BatValue < BATTERY_LOW_LEVEL)						//<2.3V
		{
			//红灯慢闪(系统电量低指示灯)
			RedLed_Config(RED_FAST_FLASH_1);
			OS_timer_SetEvt(EVT_REDLED_CONTROL);
			
			//6秒无动作自动休眠
			OS_timer_start( EVT_ENTER_SLEEP, 600, false );		// 6s无动作自动休眠
			WakeupSource = 0;
			//return;
		}
		else
		{
			if(WakeupSource&USER_BUTTON)			//按键唤醒
			{
				WakeupSource ^= USER_BUTTON;
				#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
				dbg_printf("Wakeup from power-key\r\n");
				#endif
				start_tx = 0;		
				UserEncryptInit();
				
				key_enable = 1;		//按键检测使能

			  //SPCStart();
				
				Systerm_States |= STARTUP;

				
				//短按唤醒蓝牙，红交替闪烁(广播提示) 1S闪烁一次
				if(0==(Systerm_States&POWERON))
				{
					if(GPIO_Pin_Read(U32BIT(GPIO_USER_KEY)))
					{
						RedLed_Config(RED_SLOW_FLASH_1);
						OS_timer_SetEvt(EVT_REDLED_CONTROL);

						Systerm_States |= POWERON;
						OS_timer_start( EVT_START_DEVICE, 2, false );		//启动设备，开启广播
						
						dbg_printf("SystemState:0x%02x\r\n", Systerm_States);
					}
							
				}
			
			}
			else if(WakeupSource&lock_cut){
				WakeupSource ^= lock_cut;

				timerSendFlag=0;//YCY:260101
				
				start_tx = 0;		
				UserEncryptInit();
				
				key_enable = 1;		//按键检测使能				
				Systerm_States |= STARTUP;

				if(GPIO_Pin_Read(U32BIT(lock_hall)))//
				{

					RedLed_Config(RED_SLOW_FLASH_1);
					OS_timer_SetEvt(EVT_REDLED_CONTROL);

					Systerm_States |= POWERON;
					OS_timer_start( EVT_START_DEVICE, 2, false );		//启动设备，开启广播
					flg_cutup = 1;
				}
				else{
					OS_timer_start(EVT_ENTER_SLEEP, 1, 0);
				}			
			}
			else if(WakeupSource&LOCK_CHECK)	//上锁唤醒
			{
				WakeupSource ^= LOCK_CHECK;

				if(0==key_state_lock)		//如果是开锁的状态
				{
					if(1==GetInputStopR())
					{
						Systerm_States |= AUTOLOCK;
						#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
						dbg_printf("Wakeup from autolock\r\n");
						#endif
						OS_timer_start(EVT_AUTO_LOCK, 2, false);				//20mS 后自动停止
						key_state_lock = 1;
					}
				}
			}
			else if(WakeupSource&RTC_ALARM)	//RTC唤醒
			{
				WakeupSource ^= RTC_ALARM;
				
				key_enable = 1;		//按键检测使能
				
				if(0==(Systerm_States&BATDISCHG))	//
				{
					Systerm_States |= BATDISCHG;			//系统状态
					BatDischargeOn();								//开始放电
					#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
					dbg_printf("Wakeup from RTC\r\n");
					#endif
					// OS_timer_start( EVT_BAT_DISCHARGE, 12000, false );	//120s后停止
							OS_timer_start( EVT_BAT_DISCHARGE, 3000, false );	//30s后停止			
					//OS_timer_start( EVT_BAT_DISCHARGE, 1000, false );	//放电10s后停止 (20分钟一次)
				}
			}
			else if(WakeupSource & RTC_4Greport){
				WakeupSource ^= RTC_4Greport;
				
				key_enable = 1;		//按键检测使能	
				Systerm_States |= STARTUP;
				flg_cmd_gpsdata = 1;

				// if(flg_4g_EN==0){//强制开4G
					RedLed_Config(RED_LED_OFF);					
					e103w08b_init();timerSendFlag=1;//YCY:260101
					UartEn(true);	
					OS_timer_stop(EVT_ENTER_SLEEP);
					OS_timer_start( EVT_START_DEVICE, 2, false );		//启动设备，开启广播 主要为延时60秒进休眠
				// }						
							
			}
			else		//其他情况
			{
				WakeupSource = 0;
			}
					
		}
	}
	
	if(key_enable)
	{
		if(0==GPIO_Pin_Read(U32BIT(GPIO_USER_KEY)))		//按键按下了 
		{
			if(!key_lock)
			{
				key_state = KEY_DOWN;		//按下
			}
			if(key_delay_cnt>=200+400)		//2S+4s长按
			{
				_user.key_7cnt = 0;
				_user.key_7delay = 0;

				key_delay_cnt = 0;
				key_lock = 1;
				key_state = KEY_UP;		//按下
				
				// delay_ms(100);
				// SystemReset();		//长按重启
				// delay_ms(1000);
				if(flg_4g_EN==0){
					Systerm_States |= STARTUP;	//				
					RedLed_Config(RED_LED_OFF);					
					e103w08b_init();
					UartEn(true);	
					OS_timer_stop(EVT_ENTER_SLEEP);
					OS_timer_start( EVT_START_DEVICE, 2, false );		//启动设备，开启广播 主要为延时60秒进休眠
				}
				
			}
		}
		else
		{	

			if(key_delay_cnt>0 && key_delay_cnt<500)
			{
				
				if(_user.key_7delay){
					_user.key_7delay = 80;
					if(++_user.key_7cnt>=3){
						// delay_ms(100);
						// SystemReset();		//重启
						// delay_ms(1000);	
						flg_reset = 1;					
					}
				}
				else{
					_user.key_7cnt = 0;
					_user.key_7delay = 50;
				}

				if(!connect_flag)
				{

					if(Systerm_States & STARTUP)
					{
						Systerm_States^=STARTUP;
					}

					OS_timer_stop(EVT_ENTER_SLEEP);
					

					// if(flg_4g_EN==0){
					// 	e103w08b_init();
					// 	UartEn(true);	
					// }		

					//短按唤醒蓝牙，红交替闪烁(广播提示) 1S闪烁一次
					RedLed_Config(RED_SLOW_FLASH_1);
					OS_timer_SetEvt(EVT_REDLED_CONTROL);
					flg_needred3 = 1;
					
					Systerm_States |= POWERON;
					OS_timer_start( EVT_START_DEVICE, 2, false );		//启动设备，开启广播
				}
			}
			key_state = KEY_UP;
			key_delay_cnt = 0;
			key_lock = 0;
		}
		
		if((0==GPIO_Pin_Read(U32BIT(STATE_KEY))) && (GPIO_Pin_Read(U32BIT(lock_hall)) == 0))		//锁状态按下
		{
			uint8_t databuf[20],datalen=0;
			
			if(StateKeyState==KEY_UP)//第一次按下
			{	
				if(net4G.BC95_status == BC95_sendmessage){//4G下执行自动上锁动作
					auto_close();
				}			
				else{		
					if(ConnectPasswordCorrect)		//连接正确
					{
						lock_state = GetDoorState();	//获取锁状态
						_user.bat_volt = BatValue = GetBatCapacity();	//获取电量
						
						databuf[0] = 0xAA;
						cmd_id++;
						databuf[1] = cmd_id;				//命令id
						databuf[2] = 0x40;
						databuf[3] = BatValue;			//电量
						databuf[4] = lock_state;		//开关
						databuf[5] = 0x01;					//锁杆动作了
						
						databuf[6] = computer_sum(databuf+1,5);
						datalen = 7; 
						
						user_encrypt_notify(aes_key2, databuf, datalen);	//上报数据
					}
				}
				StateKeyState = KEY_DOWN;
			}
		}		
		else
		{
			StateKeyState = KEY_UP;
		}
	}
}


/* 唤醒按键回调函数 */
void  GPIO_callback(void)
{
	uint32_t SW;

	SW=GPIO_IRQ_CTRL->GPIO_INT; 

	if(SW&U32BIT(STATE_KEY))
	{
		#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
		dbg_printf("STATE_KEY int\r\n");
		#endif

		//WakeupSource &= ~USER_BUTTON;
		WakeupSource |= LOCK_CHECK;
	}

	if(SW&U32BIT(lock_hall))
	{
		WakeupSource |= lock_cut;		
	}
	
	if(SW&U32BIT(GPIO_USER_KEY))
	{
		#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
		dbg_printf("USER_KEY int\r\n");
		#endif

		//WakeupSource &= ~LOCK_CHECK;
		WakeupSource |= USER_BUTTON;
	}
}

void gpio_init_sleep(void)
{
	if(GetInputStopF())
	{
		PIN_Pullup_Enable(T_QFN_48, U32BIT(STOP_KEY1));
	}
	else 
	{
		PIN_Pullup_Disable(T_QFN_48, U32BIT(STOP_KEY1));	
	}
	
	if(GetInputStopR())
	{
		PIN_Pullup_Enable(T_QFN_48, U32BIT(STOP_KEY2));
	}
	else 
	{
		PIN_Pullup_Disable(T_QFN_48, U32BIT(STOP_KEY2));	
	}
}


/* 进入sleep前设置唤醒源 */
void WakupConfig_BeforeSleep(void)
{
	struct gap_wakeup_config pw_cfg;
	uint32_t io_int=0,io_inv=0;

SLEEPCONFIG:
		wdt_clear();
		PIN_Pullup_Enable(T_QFN_48, U32BIT(GPIO_USER_KEY)|U32BIT(STATE_KEY));			//设置为输入模式
		GPIO_Set_Input(U32BIT(GPIO_USER_KEY)|U32BIT(STATE_KEY), 0);								//反向

注意：在配置深度睡眠之前必须保证作为唤醒源的GPIO管脚对MCU而言是低电平，也就是说如果在睡眠的时候唤醒源GPIO外部状态为高电平，
这里就必须要配置上gpio模块输入的反相器，也就是 PIN_CONFIG->PIN_13_POL寄存器，否则芯片不能够正确进入睡眠！

		PIN_Pullup_Disable(T_QFN_48, U32BIT(lock_hall));	

		if(GPIO_Pin_Read(U32BIT(lock_hall)))
		{
			GPIO_Set_Input(U32BIT(lock_hall), U32BIT(lock_hall));			//反向
		}
		else
		{
			GPIO_Set_Input(U32BIT(lock_hall), 0);							//反向			
		}

		delay_ms(2);
		
		if(GPIO_Pin_Read(U32BIT(lock_hall))) 
		{
			goto SLEEPCONFIG;
		}

		io_int = U32BIT(GPIO_USER_KEY);
	
		if(GPIO_Pin_Read(U32BIT(GPIO_USER_KEY)))
		{
			io_inv |= U32BIT(GPIO_USER_KEY);
		}
		else
		{
			io_inv &= ~U32BIT(GPIO_USER_KEY);
		}
	
		if(!GetInputStopF() && GetInputStopR())					//正常开锁状态下，StopF=0;StopR=1
		{
			PIN_Pullup_Enable(T_QFN_48, U32BIT(STOP_KEY2));
			PIN_Pullup_Disable(T_QFN_48, U32BIT(STOP_KEY1));
			
			GPIO_Set_Input(io_int, io_inv);		//反向
			delay_ms(2);
			
			if(GPIO_Pin_Read(U32BIT(GPIO_USER_KEY))) 
			{
				goto SLEEPCONFIG;
			}
		}
		else if(GetInputStopF() && !GetInputStopR()) 		//正常关锁状态下，禁止io中断
		{
			PIN_Pullup_Enable(T_QFN_48, U32BIT(STOP_KEY1));
			PIN_Pullup_Disable(T_QFN_48, U32BIT(STOP_KEY2));
			
			GPIO_Set_Input(io_int, io_inv);		//反向
			delay_ms(2);

			if(GPIO_Pin_Read(U32BIT(GPIO_USER_KEY))) 
			{
				goto SLEEPCONFIG;
			}
		}
		else if(GetInputStopF() && GetInputStopR())			//关状态下，堵转导致StopF=1;StopR=1
		{
			PIN_Pullup_Enable(T_QFN_48, U32BIT(STOP_KEY2));
			PIN_Pullup_Enable(T_QFN_48, U32BIT(STOP_KEY1));
			
			GPIO_Set_Input(io_int, io_inv);		//反向
			delay_ms(2);
			
			if(GPIO_Pin_Read(U32BIT(GPIO_USER_KEY))) 
			{
				goto SLEEPCONFIG;
			}
		}
		else if(!GetInputStopF() && !GetInputStopR())																					//其他止转开关出错的情况下
		{
			PIN_Pullup_Disable(T_QFN_48, U32BIT(STOP_KEY2));
			PIN_Pullup_Disable(T_QFN_48, U32BIT(STOP_KEY1));
			
			GPIO_Set_Input(io_int, io_inv);		//反向
			delay_ms(2);
			
			if(GPIO_Pin_Read(U32BIT(GPIO_USER_KEY))) 
			{
				goto SLEEPCONFIG;
			}
		}
		
		if(flg_do_closelockOK){//仅关锁才开启hall中断
		 	io_int |= U32BIT(lock_hall);
		}
	
		pw_cfg.wakeup_type = SLEEP_WAKEUP;
		pw_cfg.wdt_wakeup_en = (bool)false;
		pw_cfg.rtc_wakeup_en = (bool)true;
		pw_cfg.timer_wakeup_en = (bool)false;
		pw_cfg.gpi_wakeup_en = (bool)true;
		pw_cfg.gpi_wakeup_cfg = WAKEUP_PIN;		//中断唤醒pin

		if(flg_do_closelockOK){//仅关锁才开启hall中断
			pw_cfg.gpi_wakeup_cfg |= WAKEUP_hall;
		}
		WakeupConfig(&pw_cfg);
		delay_ms(5);
		//清除中断标志位
		GPIO_IRQ_CTRL->GPIO_INT_CLR = 0xFFFFFFFF;
		

		io_irq_enable(io_int, GPIO_callback);	//使能按键中断
}

/* 测试程序 */
void test_wakeup(void)
{
	uint8_t key[16]={ 0x64, 0x69, 0x4E, 0x86, 0x1A, 0xE8, 0x11, 0x22, 0x33,0x44,0x55, 0x66,0x77, 0x88, 0x99, 0xaa};
	uint8_t data[16]={0x38,0x3f,0x51,0x59,0xaf,0x68,0xe2,0x3,0x7a,0xe,0x55,0xf,0x37,0xd2,0xc5,0x43};
	
	uint8_t buff[16];
	
	my_aes_decrypt(key, data, buff);
	#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
	DBGHEXDUMP("decrypted:\r\n",buff,16);
	#endif
}


/* 主要任务 */
void user_task(void)
{
	/* 初始化外设、开启蓝牙广播 */
	if(TIMER_EVENT & EVT_START_DEVICE)
	{	

		if(flg_4g_EN==0){
			if(!ADV_Flag)
			{
				rf_restart();	//开始广播
				
				#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
				dbg_printf("\r\nStartAdv\r\n");
				#endif
				
				ADV_Flag = 1;	//标记
			}
		}
		else{//4G下关蓝牙广播
			rf_stop();
			ADV_Flag= 0;
		}
		
		// 61S内 如果建立连接，则进入休眠
		if(flg_4g_EN==0){
			if(flg_cutup==1){
				flg_cutup = 0;
				OS_timer_start(EVT_ENTER_SLEEP, 400, false);//4秒
			}
			else{
				OS_timer_start(EVT_ENTER_SLEEP, 2000, false);
			}
		}
		else{
			OS_timer_start(EVT_ENTER_SLEEP, 6000*2, false);
		}
		TIMER_EVENT ^= EVT_START_DEVICE;
	}
	
	/* 停止所有功能进入关机模式 */
	if(flg_err_cutalarm==0){
		if(TIMER_EVENT & EVT_ENTER_SLEEP)
		{
			TIMER_EVENT ^= EVT_ENTER_SLEEP;
			
			#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
			dbg_printf("\r\nbefore enter sleep...\r\n");
			#endif
				
			//如果处于连接，则断开连接
			if(connect_flag)
			{
				#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
				dbg_printf("\r\nDisconnect...\r\n");
				#endif
				DisConnect();
			}
			
			//停止广播
			if(ADV_Flag&&!connect_flag)
			{
				ADV_Flag = 0;
				rf_stop();
				#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
				dbg_printf("\r\nStopAdv\r\n");
				#endif
			}
			
			//关闭所有的led灯
			AllLedOff();

			GPIO_Pin_Clear(U32BIT(PWR_4G));	
			flg_4g_EN = 0;//这样就能关掉4G灯
			net4G.BC95_status = BC95_offline;//必需清   否测可以发生自动上锁动作
		  //wdt_disable();//YCY
			// if(flg_needred3){
			// 	flg_needred3 = 0;
			// 	redledblink3();
			// }

			start_tx = 0;		//不认发送Notify
				
			lock_state = GetDoorState();
			
			if(lock_state == 0)	//锁闭合状态，感应按键唤醒使能
			{
				#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
				dbg_printf("lock:CLOSE\r\n");
				#endif
			}
			else							//锁打开状态，锁梁手动上锁唤醒使能
			{
				#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
				dbg_printf("lock:OPEN\r\n");
				#endif
			}
			
			if(!connect_flag)
			{
				if((Systerm_States&BATDISCHG) || (Systerm_States&AUTOLOCK))
				{
					OS_timer_start(EVT_ENTER_SLEEP, 100, 0);	//1s 后自动进入休眠
				}
				else
				{
					key_enable = 0;
					
					Disable_Timer2_2ms();	//停止Timer2 10ms定时
					
					ADV_Flag = 0;
				
					Stop_Timer_All();
					
					RTC_EVT_Start(RTCEVT_72h);	//启动放电事件
					RTC_EVT_Start(RTCEVT_24h);	//启动自动上报功能		

					#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
					dbg_printf("\r\nenter sleep!!!\r\n");
					#endif
					
					WakupConfig_BeforeSleep();			//设置唤醒源			
					
	//				if(Systerm_States & OTAMODE)
	//				{
	//					dbg_printf("\r\nSystem Reset!!!\r\n");
	//					DelayMS(5000);
	//					SystemReset();
	//					DelayMS(5000);
	//				}					

					Systerm_States=SLEEP;
				}
				
				
				//SystemSleep();				//系统睡眠
			}
			else
			{
				connect_flag = 0;
				OS_timer_start(EVT_ENTER_SLEEP, 200, 0);	//1s 后自动进入休眠
			}	
		}
	}
	
	
	/* 自动上锁功能 */
	if(TIMER_EVENT & EVT_AUTO_LOCK)
	{
		#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
		dbg_printf("\r\nlock on...\r\n");
		#endif
		
		_user.bat_volt = BatValue = GetBatCapacity();	//读取电量
		
		if(BatValue >=	BATTERY_LOW_LEVEL)
		{
			if(1==GetInputStopR())					//如果锁是开的 
			{
				MotorStop();
				MotorStartReverse(); 				//关锁马达反转
				OS_timer_start(EVT_MOTOR_STOP, 200, 0);	//5S 后自动停止
				
				#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
					dbg_printf("stopkey check\r\n");
				#endif
				
				OS_timer_SetEvt( EVT_STATE_CHECK );		//启动锁状态检测事件
			}
			else
			{
				key_state_lock = 0;
				if(Systerm_States&AUTOLOCK)
					Systerm_States ^= AUTOLOCK;
				//OS_timer_start(EVT_MOTOR_STOP, 2, 0);	//20mS 后自动停止
			}
		}
		else
		{
			BlueLed_Config(BLUE_LED_OFF);
			OS_timer_SetEvt(EVT_BLUELED_CONTROL);
			
			RedLed_Config(RED_FAST_FLASH_1);
			OS_timer_SetEvt(EVT_REDLED_CONTROL);
			
			OS_timer_stop(EVT_ENTER_SLEEP);
			OS_timer_start(EVT_ENTER_SLEEP, 600, 0);	//6S 后自动停止
		}
	
		TIMER_EVENT ^= EVT_AUTO_LOCK;
	}
	
	
	/* 马达停止 */
	if(TIMER_EVENT & EVT_MOTOR_STOP)
	{
		uint8_t databuf[20],datalen=0;
		
		#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
		dbg_printf("\r\nmotor stop...\r\n");
		#endif
		
		MotorStop();
		
		lock_state = GetDoorState();	//获取锁状态
		_user.bat_volt = BatValue = GetBatCapacity();	//获取电量
		
		if(lock_state)
		{
			if(LockOpen_Report)	//如果是开锁命令
			{
				RedLed_Config(RED_THREE_FLASH_1);						
				OS_timer_SetEvt(EVT_REDLED_CONTROL);
			}
		}
		else
		{
			if(0==ConnectPasswordCorrect)		//
			{
				BlueLed_Config(BLUE_SLOW_FLASH_ONE_1);						
				OS_timer_SetEvt(EVT_BLUELED_CONTROL);
			}
		}
		
		#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
		dbg_printf("report state...\r\n");
		#endif
		
		if(LockOpen_Report)
		{
			//发送上锁状态
			databuf[0] = 0xAA;
			cmd_id ++;
			databuf[1] = cmd_id;				//命令id
			databuf[2] = 0x30;
			databuf[3] = 0x00;
			databuf[4] = BatValue;			//电池电量
			databuf[5] = lock_state;		//锁状态		
			databuf[6] = 0x00;					//电池盒		
			databuf[7] = computer_sum(databuf+1,6);
			datalen = 8; 
			//LockOpen_Report = 0;
		}
		else
		{
			databuf[0] = 0xAA;
			cmd_id++;
			databuf[1] = cmd_id;				//命令id
			databuf[2] = 0x40;
			databuf[3] = BatValue;			//电量
			databuf[4] = lock_state;		//开关
			databuf[5] = 0x00;					//电池盒
			
			databuf[6] = computer_sum(databuf+1,5);
			datalen = 7; 
			databuf[0] = 0xAA;
			cmd_id ++;
			databuf[1] = cmd_id;				//命令id
			databuf[2] = 0x31;
			databuf[3] = 0x00;
			databuf[4] = BatValue;			//电池电量
			databuf[5] = lock_state;		//锁状态
			databuf[6] = 0x00;					//电池盒		
			databuf[7] = computer_sum(databuf+1,6);
			datalen = 8; 
			LockOpen_Report = 0;
		}
		if(ConnectPasswordCorrect)		//连接正确
		{
			user_encrypt_notify(aes_key2, databuf, datalen);	//上报数据
		}
		
		if(Systerm_States&AUTOLOCK)	 //上锁动作完成
		{
			Systerm_States ^= AUTOLOCK;
			
			key_state_lock = 0;
			flg_openlock = 0;
			
			if(0==(Systerm_States&POWERON))
			{
				if(flg_4g_EN){
					flg_cmd_gpsdata = 1;
					OS_timer_stop(EVT_ENTER_SLEEP);	//
					OS_timer_start(EVT_ENTER_SLEEP, 400, 0);	// 4秒
				}
				else{
					OS_timer_stop(EVT_ENTER_SLEEP);	//
					OS_timer_start(EVT_ENTER_SLEEP, 200, 0);	//4S 后进入休眠
				}
			}
		}
		else//开锁动作完成
		{

			if(flg_openlock){
				flg_openlock = 0;
				if(flg_4g_EN){
					OS_timer_stop(EVT_ENTER_SLEEP);	//
					OS_timer_start(EVT_ENTER_SLEEP, 6000, 0);	// 6000*2 120s后进入休眠
				}
			}	
			else{		
				OS_timer_stop(EVT_ENTER_SLEEP);	//
				OS_timer_start(EVT_ENTER_SLEEP, 200, 0);	//4S 后进入休眠
			}
		}
		
		TIMER_EVENT ^= EVT_MOTOR_STOP;
	}
	
	
	/* 电池72小时放电一次 */
	if(TIMER_EVENT & EVT_BAT_DISCHARGE)
	{
		dischrg_flag = 0;
		BatDischargeOff();		//关闭放电
		
		if(Systerm_States & BATDISCHG)
		{
			Systerm_States ^= BATDISCHG;	//清除
		}
		if(0==(Systerm_States & POWERON))
		{
			OS_timer_stop(EVT_ENTER_SLEEP);
			OS_timer_start(EVT_ENTER_SLEEP, 5, 0);	//50mS 后进入休眠
		}
		
		TIMER_EVENT ^= EVT_BAT_DISCHARGE;
	}
	
	/* 红灯控制 */
	if(TIMER_EVENT & EVT_REDLED_CONTROL)
	{
		RedLedEventHandle();
		TIMER_EVENT ^= EVT_REDLED_CONTROL;
	}
	
	/* 蓝灯控制 */
	if(TIMER_EVENT & EVT_BLUELED_CONTROL)
	{
		BlueLedEventHandle();
		TIMER_EVENT ^= EVT_BLUELED_CONTROL;
	}
	
	/* 锁开/关状态检测(开锁任务时不断检测状态) */
	if(TIMER_EVENT & EVT_STATE_CHECK)
	{
		
		OS_timer_start(EVT_STATE_CHECK, 2, 0);
		
		if(MotorState==MOTOR_FORWARD)		//马达处于正转 开锁
		{
			if(0 == GetInputStopF())	//检测到正转 停止位为"0"
			{
				//SystemParameter.lockstate = GetDoorState();
				OS_timer_stop(EVT_STATE_CHECK);	//停止检测定时器
				//OS_timer_SetEvt( EVT_MOTOR_STOP );		//启动马达停转事件
				OS_timer_stop(EVT_MOTOR_STOP);				//停止马达stop定时器
				OS_timer_start(EVT_MOTOR_STOP, 5, 0);
				//led_set_status(BLUE_THREE_FLASH_1);		//开锁后蓝灯闪烁三次 约550ms
			}
		}
		else if(MotorState==MOTOR_REVERSE)//马达处于反转
		{
			if(0 == GetInputStopR())	//检测到反转 停止位为"0"
			{
				//SystemParameter.lockstate = GetDoorState();
				OS_timer_stop(EVT_STATE_CHECK);				//停止检测
				//OS_timer_SetEvt( EVT_MOTOR_STOP );		//启动马达停转事件
				OS_timer_stop(EVT_MOTOR_STOP);				//停止马达stop定时器	
				OS_timer_start(EVT_MOTOR_STOP, 2, 0);
			}
		}
		else		//马达处于停止
		{
			OS_timer_stop(EVT_STATE_CHECK);	//停止检测
		}
		
		
		TIMER_EVENT ^= EVT_STATE_CHECK;
	}
	
	/* 锁蓝牙停止 */
	if(TIMER_EVENT & EVT_STOP_BLE)
	{				
		//connected_event();
		if(Systerm_States&POWERON)
			Systerm_States ^= POWERON;
		if(Systerm_States&AUTOLOCK)
			Systerm_States ^= AUTOLOCK;
		
		TIMER_EVENT ^= EVT_STOP_BLE;
	}
}


/* 接收手机端命令 */
uint8_t gatt_read_AppCB(uint8_t *p_data, uint8_t pdata_len)
{
	uint8_t databuf[16],datalen=0;	//databuf 发送缓存区,datalen 发送数据长度
	uint8_t aes_buff[16],p_value[16],len=0;
	uint8_t key[16];
	
	if(pdata_len!=16)
		return 1;
	
	if(GetConnectTimeSuccess)
	{
		memcpy(key, aes_key2, 16);
	}
	else
	{
		memcpy(key, aes_key1, 16);
	}
	
	//__disable_irq();
	aes_decrypt_ecb(key, 16, p_data, aes_buff, 1);	//
	//__enable_irq();
	
	#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
		DBGHEXDUMP("decrypted:\r\n",aes_buff,16);
	#endif
	
	if(aes_buff[1]<14)
	{
		//第一个字节表示有效数据长度
		len = aes_buff[1];
		memcpy(p_value, aes_buff+2, len);	//
		
	}
	else
	{
		return 1;
	}
	
	if(GetConnectTimeSuccess==true)				//接收到了app发送来的时间
	{
		if(ConnectPasswordCorrect==true)		//如果连接密码正确
		{
			if(len==4)			//强制休眠命令
			{
				//BatValue = GetBatCapacity();
				if((p_value[0] == 0x55) && (p_value[2] == 0x50) && (p_value[3] == computer_sum(p_value+1,2)))
				{
					_user.bat_volt =  BatValue = GetBatCapacity();
						
					databuf[0] = 0xAA;
					cmd_id ++;
					databuf[1] = cmd_id;				//命令id
					databuf[2] = 0x50;
					databuf[3] = BatValue;			//电量
					databuf[4] = lock_state;		//开关				
					databuf[5] = 0;							//电池盒
					
					databuf[6] = computer_sum(databuf+1,5);
					datalen = 7; 
					
					#ifdef DEBUGEN
					co_printf("Send Command:\r\n");
					show_reg(databuf, datalen, 1);
					#endif
					
					user_encrypt_notify(aes_key2, databuf, datalen);		//加密后发送
				}
			}
			else if(len==5)	//开锁命令 查询命令 密码设置命令
			{
				_user.bat_volt = BatValue = GetBatCapacity();
				
				if((p_value[0] == 0x55) && (p_value[4] == computer_sum(p_value+1,3)))
				{
					if(p_value[2] == 0x21)				//密码设置
					{
						databuf[0] = 0xAA;
						cmd_id ++;
						databuf[1] = cmd_id;		//命令id
						databuf[2] = 0x21;					//命令
						databuf[3] = 00;						//成功		
						databuf[4] = computer_sum(databuf+1,3);
						datalen = 5; 
						
						user_encrypt_notify(aes_key2, databuf, datalen);		//加密后发送
					}
					else if(p_value[2] == 0x30)		//开锁命令
					{
						LockOpen_Report = 1;		//开锁回复允许
						
						_user.bat_volt = BatValue = GetBatCapacity();
						
						OS_timer_stop(EVT_ENTER_SLEEP);
						
						BlueLed_Config(BLUE_LED_OFF);
						OS_timer_SetEvt(EVT_BLUELED_CONTROL);

						//电池电量充足才会启动马达
						if(BatValue >= BATTERY_LOW_LEVEL)
						{
							//OS_timer_start(EVT_MOTOR_STOP, 500, false);	//5S 后自动停止			
							if(1==GetInputStopF())
							{
								MotorStop();
								MotorStartForward();
								OS_timer_start(EVT_MOTOR_STOP, 200, false);	//5S 后自动停止
								OS_timer_SetEvt( EVT_STATE_CHECK );					//启动锁状态检测事件
								OS_timer_start(EVT_ENTER_SLEEP, 3000, false);	//30s
								//flg_openlock = 1;
							}
							else 
							{
								OS_timer_start(EVT_MOTOR_STOP, 2, false);	//20mS 后自动停止
							}
						}
						else
						{
							RedLed_Config(RED_FAST_FLASH_1);
							OS_timer_SetEvt(EVT_REDLED_CONTROL);
							
							OS_timer_start(EVT_ENTER_SLEEP, 600, false);	//6s后进入休眠
						}				
					}
					else if(p_value[2] == 0x31)		//上锁命令
					{
						LockOpen_Report = 0;			//上锁回复允许
						
						_user.bat_volt = BatValue = GetBatCapacity();
						
						//CloseCMDReceived = 1;		 //上锁命令已经接收
						
						OS_timer_stop(EVT_ENTER_SLEEP);
						
						//BlueLed_Config(BLUE_LED_OFF);
						//OS_timer_SetEvt(EVT_BLUELED_CONTROL);
						
						

						//电池电量充足才会启动马达
						if(BatValue >= BATTERY_LOW_LEVEL)
						{
							//OS_timer_start(EVT_MOTOR_STOP, 500, false);	//5S 后自动停止			
							if(0==key_state_lock)		
							{
								if(1==GetInputStopR())	//如果是开锁的状态
								{
									Systerm_States |= AUTOLOCK;
									#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
									dbg_printf("Wakeup from autolock\r\n");
									#endif
									OS_timer_start(EVT_AUTO_LOCK, 0, false);	//OS_timer_start(EVT_AUTO_LOCK, 2, false);				//20mS 后自动停止
									key_state_lock = 1;
								}
								else 
								{
									OS_timer_start(EVT_MOTOR_STOP, 2, false);	//20mS 后自动停止
								}
							}
							//OS_timer_start(EVT_ENTER_SLEEP, 2100, false);		//21s后进入休眠
						}
						else		//低电量
						{
							RedLed_Config(RED_FAST_FLASH_1);
							OS_timer_SetEvt(EVT_REDLED_CONTROL);
							
							OS_timer_start(EVT_ENTER_SLEEP, 600, false);	//6s后进入休眠
						}				
					}
					else if(p_value[2] == 0x40)		//状态查询命令
					{
						_user.bat_volt = BatValue = GetBatCapacity();
						
						databuf[0] = 0xAA;
						cmd_id ++;
						databuf[1] = cmd_id;				//命令id
						databuf[2] = 0x40;
						databuf[3] = BatValue;			//电量
						databuf[4] = lock_state;		//开关
						databuf[5] = 0x00;					//电池盒
						
						databuf[6] = computer_sum(databuf+1,5);
						datalen = 7; 
						
						#ifdef DEBUGEN
						co_printf("Send Command:\r\n");
						show_reg(databuf, 7, 1);
						#endif
						
						user_encrypt_notify(aes_key2, databuf, datalen);		//加密后发送
					}
			else if(len==10)
			{
				// ===== 0x21 SET PASSWORD (len==10) =====
				if((p_value[0] == 0x55) && (p_value[2] == 0x21) && (p_value[9] == computer_sum(p_value+1,8)))
				{
					uint32_t newpass = (uint32_t)p_value[3] * 100000
					                 + (uint32_t)p_value[4] * 10000
					                 + (uint32_t)p_value[5] * 1000
					                 + (uint32_t)p_value[6] * 100
					                 + (uint32_t)p_value[7] * 10
					                 + (uint32_t)p_value[8];
					SystemParameter.password[0] = (uint8_t)(newpass >> 16);
					SystemParameter.password[1] = (uint8_t)(newpass >> 8);
					SystemParameter.password[2] = (uint8_t)(newpass);
					UpdateFlashParameter();
					dbg_printf("Password changed!\r\n");
					databuf[0] = 0xAA;
					cmd_id++;
					databuf[1] = cmd_id;
					databuf[2] = 0x21;
					databuf[3] = 0x00;
					databuf[4] = computer_sum(databuf+1, 3);
					datalen = 5;
					user_encrypt_notify(aes_key2, databuf, datalen);
				}
			}
					else if(p_value[2] == 0x41){//蓝牙增加1条指令查询锁号，回传锁号
						databuf[0] = 0xAA;
						cmd_id ++;
						databuf[1] = cmd_id;				//命令id
						databuf[2] = 0x41;
						databuf[3] = _user.lock_sn8array.data8[0];
						databuf[4] = _user.lock_sn8array.data8[1];
						databuf[5] = _user.lock_sn8array.data8[2];
						databuf[6] = _user.lock_sn8array.data8[3];
						databuf[7] = _user.lock_sn8array.data8[4];
						databuf[8] = _user.lock_sn8array.data8[5];
						databuf[9] = _user.lock_sn8array.data8[6];
						databuf[10] = _user.lock_sn8array.data8[7];

						databuf[11] = computer_sum(databuf+1,10);
						datalen = 12; 
						user_encrypt_notify(aes_key2, databuf, datalen);		//加密后发送
					}
				}
			}
		}
		else 
		{
			if(len==10)
			{
				//接收到密码连接命令
				if((p_value[0] == 0x55) && (p_value[2] == 0x20) && (p_value[9] == computer_sum(p_value+1,8)))
				{
					//cmd_id = p_value[1];
					//判断密码是否正确
					if(p_value[3]==0&&p_value[4]==0&&p_value[5]==0)
					{
						if(p_value[6]==0&&p_value[7]==0&&p_value[8]==0)
						{
							ConnectPasswordCorrect = true;
							
							OS_timer_stop(EVT_ENTER_SLEEP);
							
							databuf[0] = 0xAA;
							cmd_id++;
							databuf[1] = cmd_id;		//命令id
							databuf[2] = 0x02;
							databuf[2] = 0x20;
							databuf[3] = 0x00;					//密码正确
							databuf[4] = computer_sum(databuf+1,3);
							datalen = 5; 
							
							#ifdef DEBUGEN
							co_printf("Send Command:\r\n");
							show_reg(databuf, 5, 1);
							#endif
							
							BlueLed_Config(BLUE_SLOW_FLASH_1);
							OS_timer_SetEvt(EVT_BLUELED_CONTROL);
							
							user_encrypt_notify(aes_key2, databuf, datalen);		//加密后发送

							OS_timer_start(EVT_ENTER_SLEEP, 6000, false);	//密码验证成功后，1分钟后无操作进入休眠
						}
						else 
						{
							//密码验证错误
							databuf[0] = 0xAA;
							cmd_id ++;
							databuf[1] = cmd_id;		//命令id
							databuf[2] = 0x02;
							databuf[2] = 0x20;
							databuf[3] = 0x01;					//密码错误
							databuf[4] = computer_sum(databuf+1,3);
							datalen = 5; 
							
							user_encrypt_notify(aes_key2, databuf, datalen);		//加密后发送
						}
					}
					else
					{
						//密码验证错误
						databuf[0] = 0xAA;
						cmd_id ++;
						databuf[1] = cmd_id;		//命令id
						databuf[2] = 0x02;
						databuf[2] = 0x20;
						databuf[3] = 0x01;					//密码错误
						databuf[4] = computer_sum(databuf+1,3);
						datalen = 5; 
						
						#ifdef DEBUGEN
						co_printf("Send Command:\r\n");
						show_reg(databuf, 5, 1);
						#endif
						
						user_encrypt_notify(aes_key2, databuf, datalen);		//加密后发送
					}
					
				}
				else 
				{
					//数据加密错误，无效数据
					
				}
			}
		}
	} 
	else 
	{
		if(len == 10)		//55 cmd_id 0x10 年 月 日 时 分 秒 sum
		{
			//连接成功APP发送连接密码给设备(连接密码验证通过)
			if((p_value[0] == 0x55) && (p_value[2] == 0x10) && (p_value[9] == computer_sum(p_value+1,8)))
			{
				GetConnectTimeSuccess = true;	//验证通过，接下来使用密钥key2
				cmd_id = p_value[1];
				cmd_id++;
				OS_timer_stop(EVT_ENTER_SLEEP);
				
				RedLed_Config(RED_LED_OFF);
				OS_timer_SetEvt(EVT_REDLED_CONTROL);		//关闭led显示
				
				//回复指令
				databuf[0] = 0xAA;
				databuf[1] = cmd_id;		//命令id
				databuf[2] = 0x10;
				databuf[3] = computer_sum(databuf+1,2);
				datalen = 4; 
				
				user_encrypt_notify(aes_key1, databuf, datalen);		//加密后发送
				
				aes_key2[10] = p_value[3];	//更新密钥key2——密钥1的最后六个字节替换为年、月、日、时、分、秒
				aes_key2[11] = p_value[4];
				aes_key2[12] = p_value[5];
				aes_key2[13] = p_value[6];	//更新密钥key2
				aes_key2[14] = p_value[7];
				aes_key2[15] = p_value[8];

				OS_timer_start(EVT_ENTER_SLEEP, 1000, false);	//重新定时6s等待连接密码

			}
			else	//数据格式 校验和验证错误
			{
				OS_timer_stop(EVT_ENTER_SLEEP);
				OS_timer_start(EVT_ENTER_SLEEP, 6000, false);	//重新定时6s等待连接密码
			}
		}
	}
	
	return 0;
}

void GATT_ReadWrite_Process(void)
{
	gatt_read_AppCB(&gatt_buff[1], gatt_buff[0]);
}

void FeedDog(void)
{
   WDG_OUT_H();
	 delay_ms(20);
	 WDG_OUT_L();
}


