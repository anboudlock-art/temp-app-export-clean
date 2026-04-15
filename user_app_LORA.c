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

// // #include "e103w08b.h" // replaced by lora_e220.h // replaced by lora_e220.h
#include "lora_e220.h"
#include "user.h"
#include "cpwm.h"


#define USER_FLASH_BASE_ADDR 0	//flash data 8K
#define USER_FLASH_LENGHT 8

#define BATTERY_LOW_LEVEL		20	//�͵��� 30%  2-3V��0-100% ��2.3V

//notify�򿪱�־
uint8_t start_tx=0;	//����notify��־

uint8_t gatt_buff[20]={0};		//���յ�����

// ����id
uint8_t connect_flag=0;	//���ӳɹ���־

uint8_t WakeupSource=NONE;	//����Դ��־

uint8_t Systerm_States=SLEEP;

uint8_t EnterSleepFlag=0;

//uint8_t CloseCMDReceived=0;

/***********************
 * TYPEDEFS (���Ͷ���)
 **********************/
#define	 DEFAULT_CODE	0xA5

#define  NONE_KEY		0x00
#define  SHORT_KEY	0x01
#define  LONG_KEY		0x02

#define  KEY_DOWN		0
#define  KEY_UP     1

/*******************************
 * GLOBAL VARIABLES (ȫ�ֱ���)
 ******************************/
 
//ϵͳ����Ȳ���(�洢��flash��)
static SystemParameter_Def SystemParameter;

uint8_t lock_state = 0;

//������֤�ɹ���־

static uint8_t   cmd_id=0;

uint8_t GetConnectTimeSuccess = 0;

uint8_t ConnectPasswordCorrect = 0;

// �������ñ�־λ
//uint8_t PasscodeSetFlag = 0;

static uint8_t ADV_Flag = 0;

/******************************
 * LOCAL VARIABLES (���ر���)
 *****************************/
static uint8_t   BatValue;         		//����

//AES�ӽ���
uint8_t aes_key1[16];
uint8_t aes_key2[16];

unsigned char key_state=KEY_UP;
unsigned char key_lock = 0;

unsigned char key_enable=0;				//����ʹ�ܱ�־
unsigned int key_delay_cnt = 0;
unsigned char key_code = NONE_KEY;				//

unsigned char StateKeyState=KEY_UP;

uint8_t key_state_lock=0;
uint8_t LockOpen_Report = 0;//0 �յ���APP�ǹ���  1 �յ���APP�ǿ���

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
������: time1000ms_prc
��  ��: 1s����һ��
����ֵ: ��
���ֵ: ��
����ֵ: ��
��  ע: ��compare
**********************************************************/
void time1000ms_prc(void){
	if(lora_ctrl.state == LORA_STATE_READY){
		if(lora_ctrl.lost_cnt){
			lora_ctrl.lost_cnt--;
			if(lora_ctrl.lost_cnt==0){
				lora_ctrl.state = LORA_STATE_IDLE;
				// lora: no step counter needed
				dbg_printf("4G_OffLine");
				//flg_poweron = 0;
				_user.powerondelay1s = 200/2;
				lora_e220_deinit(); // Power off LoRa//S4G_PWR_OFF();//��λ				
			}
		}

		if(lora_ctrl.senddelaytime) lora_ctrl.senddelaytime--;
		else{
			lora_ctrl.senddelaytime = lora_ctrl.senddelaytime_set;

			flg_cmd_gpsdata = 1;

		} 
	}

}



void auto_close(void){

	
	_user.bat_volt = BatValue = GetBatCapacity();
	

	//��ص�������Ż���������
	if(BatValue >= BATTERY_LOW_LEVEL)
	{
		//OS_timer_start(EVT_MOTOR_STOP, 500, false);	//5S ���Զ�ֹͣ			
		// if(0==key_state_lock)		
		// {
			if(1==GetInputStopR())		//����ǿ�����״̬
			{
				Systerm_States |= AUTOLOCK;
				// #if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
				// dbg_printf("Wakeup from autolock\r\n");
				// #endif
				OS_timer_start(EVT_AUTO_LOCK, 0, false);	//OS_timer_start(EVT_AUTO_LOCK, 2, false);				//20mS ���Զ�ֹͣ
				key_state_lock = 1;//����״̬
			}
			// else 
			// {
			// 	OS_timer_start(EVT_MOTOR_STOP, 2, false);	//20mS ���Զ�ֹͣ
			// }
		// }
		//OS_timer_start(EVT_ENTER_SLEEP, 2100, false);		//21s���������
	}
	// else		//�͵���
	// {
	// 	RedLed_Config(RED_FAST_FLASH_1);
	// 	OS_timer_SetEvt(EVT_REDLED_CONTROL);
		
	// 	OS_timer_start(EVT_ENTER_SLEEP, 600, false);	//6s���������
	// }
}
/**********************************************************
������: bt_slot20ms
��  ��:
����ֵ: ��
���ֵ: ��
����ֵ: ��
��  ע: ��
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
������: updatedisp
��  ��:
����ֵ: ��
���ֵ: ��
����ֵ: ��
��  ע: ��
**********************************************************/
void updatedisp(void){
	if(flg_4g_EN==1){
		if(lora_ctrl.state == LORA_STATE_READY){
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
������: TIM2_INT_Callback
��  ��:
����ֵ: ��
���ֵ: ��
����ֵ: ��
��  ע: ��
**********************************************************/
static void Timer_2_callback(void)
{
	static u8 bt_slotcnt;
	hall_check();//���ϱ��������
	if(flg_4g_EN){
		lora_process(); // LoRa data processing
	}

	switch(++bt_slotcnt){
		case 1:
	
			os_timer_query();	
		break;
		case 2:
			if(_user.lock_status != lock_state){
				if(lora_ctrl.state == LORA_STATE_READY){
					flg_cmd_gpsdata = 1;
				}
				_user.lock_status = lock_state;//ͬ������
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
*��������:computer_bcc
*��������:����BCCУ���
*��ڲ���:@data,У�����ݵ���ʼ��ַ   @len,У�����ݵĳ���
*����ֵ:�������ݵ�BCCУ���
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
*��������:computer_sum
*��������:����У���
*��ڲ���:@data,У�����ݵ���ʼ��ַ   @len,У�����ݵĳ���
*����ֵ:�������ݵ�BCCУ���
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

/* UpdateFlashParameter - save SystemParameter to flash */
void UpdateFlashParameter(void)
{
	uint8_t tmp[8]={0};

	__disable_irq();

	EraseFlashData(USER_FLASH_BASE_ADDR, 1);

	tmp[0] = SystemParameter.default_id;
	tmp[1] = SystemParameter.password[0];
	tmp[2] = SystemParameter.password[1];
	tmp[3] = SystemParameter.password[2];
	tmp[4] = SystemParameter.lockstate;
	tmp[5] = SystemParameter.discharge_flag;

	WriteFlashData(USER_FLASH_BASE_ADDR, USER_FLASH_LENGHT, tmp);

	__enable_irq();
}


/* ConfigParameterDefault - read password from flash on power up */
void ConfigParameterDefault(void)
{
	uint8_t tmp[8]={0};

	ReadFlashData(USER_FLASH_BASE_ADDR, USER_FLASH_LENGHT, tmp);

	if(tmp[0] != DEFAULT_CODE)
	{
		EraseFlashData(USER_FLASH_BASE_ADDR, 1);
		SetAllParaDefault();
		tmp[0] = SystemParameter.default_id = DEFAULT_CODE;
		tmp[1] = SystemParameter.password[0];
		tmp[2] = SystemParameter.password[1];
		tmp[3] = SystemParameter.password[2];
		tmp[4] = SystemParameter.lockstate;
		tmp[5] = SystemParameter.discharge_flag;
		WriteFlashData(USER_FLASH_BASE_ADDR, USER_FLASH_LENGHT, tmp);
	}
	else
	{
		SystemParameter.default_id = tmp[0];
		SystemParameter.password[0] = tmp[1];
		SystemParameter.password[1] = tmp[2];
		SystemParameter.password[2] = tmp[3];
		SystemParameter.lockstate = tmp[4];
		SystemParameter.discharge_flag = tmp[5];
	}
	dbg_printf("CODE:0x%02x\r\n", tmp[0]);
	dbg_printf("password0:0x%02x\r\n", tmp[1]);
	dbg_printf("password1:0x%02x\r\n", tmp[2]);
	dbg_printf("password2:0x%02x\r\n", tmp[3]);
	dbg_printf("state:0x%02x\r\n", tmp[4]);
	dbg_printf("dscg:0x%02x\r\n", tmp[5]);
}

/* SetAllParaDefault - default password 000000 */
void SetAllParaDefault(void)
{
  uint32_t passwordcode = 0;  // default password: 000000

  passwordcode %= 1000000;
  SystemParameter.password[0] = (uint8_t)(passwordcode>>16);
  SystemParameter.password[1] = (uint8_t)(passwordcode>>8);
  SystemParameter.password[2] = (uint8_t)passwordcode;

  SystemParameter.lockstate = 0;
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

/* ���͸��ֻ��� */
void user_encrypt_notify(const unsigned char* Key, uint8_t *rawDataBuf, uint16_t rawDatalength)
{
	
	struct gap_att_report report;
	uint8_t send_buff[16];
	uint8_t rawdata[16];
	
//	uint8_t key[16]={ 0x64, 0x69, 0x4E, 0x86, 0x1A, 0xE8, 0x11, 0x22, 0x33,0x44,0x55, 0x66,0x77, 0x88, 0x99, 0xaa};
//	uint8_t data[16]={0xFB, 0x04, 0xAA, 0x1F, 0x10, 0x2F, 0xFC, 0xFC, 0xFC, 0xFC, 0xFC, 0xFC, 0xFC, 0xFC, 0xFC, 0xFC};
//	uint8_t buff[16];
	
	memset(send_buff, 0, 16);
		
	if(rawDatalength<14)
	{
		rawdata[0] = 0xFB;
		rawdata[1] = rawDatalength;
		memcpy(rawdata+2,rawDataBuf,rawDatalength);
		memset(rawdata+2+rawDatalength, 0xFC, 16-2-rawDatalength);
		
		#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
		DBGHEXDUMP("encrypt data:\r\n",rawdata,16);
		
		//��ʼ����		
		DBGHEXDUMP("encrypt key:\r\n",(uint8_t *)Key,16);
		#endif
		
		//__disable_irq();
		aes_encrypt_ecb(Key, 16, rawdata, send_buff, 1);
		//__enable_irq();
		
		#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
		DBGHEXDUMP("encrypted:\r\n",send_buff,16);
		#endif
		
		if(start_tx)		//���notify��
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


/* �����������Ӻ� */
void gap_connect_evnet(void)
{
	OS_timer_stop(EVT_ENTER_SLEEP);
	RedLed_Config(RED_LED_OFF);
	OS_timer_SetEvt(EVT_REDLED_CONTROL);
	GetConnectTimeSuccess = 0; 			//
	ConnectPasswordCorrect = 0;
	OS_timer_start(EVT_ENTER_SLEEP, 3000, 0);	//30s ���Զ��Ͽ�����
}


/* �����Ͽ����Ӻ� */
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
		if(flg_4g_EN==0){//4Gδ����ֱ��1S������
			OS_timer_stop(EVT_ENTER_SLEEP);
			OS_timer_start(EVT_ENTER_SLEEP, 100, 0);	//1s ���Զ���������
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

/* ���ֻ���������֤��ȷ�� */

/* �ر���������Դ,ֻ����wdt */
void WakupConfig_Disable(void)
{
//	struct gap_wakeup_config pw_cfg;
	
	io_irq_disable_all();
	
	GPIO_Set_Input(U32BIT(STATE_KEY)|U32BIT(STOP_KEY1)|U32BIT(STOP_KEY2)|U32BIT(GPIO_USER_KEY)|U32BIT(lock_hall),0);
	PIN_Pullup_Enable(T_QFN_48, U32BIT(STATE_KEY)|U32BIT(STOP_KEY1)|U32BIT(STOP_KEY2)|U32BIT(GPIO_USER_KEY));
	
	//delay_ms(2);
	
//	pw_cfg.wakeup_type = SLEEP_WAKEUP;
//	pw_cfg.wdt_wakeup_en = (bool)false;
//	pw_cfg.rtc_wakeup_en = (bool)true;
//	pw_cfg.timer_wakeup_en = (bool)false;
//	pw_cfg.gpi_wakeup_en = (bool)false;
//	pw_cfg.gpi_wakeup_cfg = 0;					//�жϻ���pin
//	WakeupConfig(&pw_cfg);
	
	//io_irq_enable(U32BIT(STATE_KEY), GPIO_callback);	//ʹ�ܰ����ж�
}


/* Ӳ����ʼ�� */
void hardware_init(void)
{
	LedBlink();

	LedInit();

	MotorInit();

	lock_state = GetDoorState();
	ConfigParameterDefault();

	ConfigParameterDefault();  // Read password from flash on power up

 if(lock_state==0){flg_do_closelockOK = 1;} //250916+
}

/*****************************************************************************
�û��س׳�ʼ��
˼·��
EncryptAndDecrypKey1�� ��Ϊ APP ���豸���ӳɹ�����ʱ��ָ�����Կ��
EncryptAndDecrypKey2 �� �� Ϊ �� �� �� ʱ �� ָ �� �� �� �� �� Կ ʹ �� �� �� �� �� �� ǰ ʱ �� �� �� ��
EncryptAndDecrypKey2 ����� 3 ���ֽڣ� ��
******************************************************************************/
void UserEncryptInit(void)
{
	uint8_t i;
	struct gap_ble_addr dev_addr;

//	dev_addr.type = PUBLIC_ADDRESS_TYPE;
//	dev_addr.addr[5]=0x64;
//	dev_addr.addr[4]=0x69;
//	dev_addr.addr[3]=0x4E;
//	dev_addr.addr[2]=0x86;
//	dev_addr.addr[1]=0x1A;
//	dev_addr.addr[0]=0xE8;
//	SetDevAddr(&dev_addr);
	
	GetDevAddr(&dev_addr);
	#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)	
		DBGHEXDUMP("MAC:\r\n",dev_addr.addr,6);
	#endif
	
	
	//��Կ 1 �� 2 ��ʼ��
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
	if(WakeupSource>0)	//�л����¼�����
	{		

		//���Ѻ���Ҫִ�е��¼�
		WakupConfig_Disable();		//��ֹ����Դ

		lock_state = GetDoorState();

		_user.key_7cnt = 0;
		_user.key_7delay = 0;

		#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
		dbg_printf("State:0x%02x\r\n", lock_state);	
		#endif
		
//		RTC_EVT_Stop(RTCEVT_72h);		 //�������ֹͣ�¼�	
//		RTC_EVT_Stop(RTCEVT_24h);		 //�������ֹͣ�¼�					
		_user.bat_volt = BatValue = GetBatCapacity(); //battery capacity init
		#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
		dbg_printf("battery:%d%%\r\n", BatValue);
		#endif
		
		wdt_enable(128*10);//128*5				//10s����ι������ 256/32.768 = 7.8ms 				
		wdt_clear();
		//OS_timer_start(EVT_FEEDWTD_ADV, 100, 1);	//��ʱ1s ι��
		
		Enable_Timer2_2ms();			//�����û�10ms��ʱ
		
		//key_enable = 1;		//�������ʹ��
		key_state = KEY_UP;
		key_delay_cnt = 0;
		key_lock = 0;
		//GPIO_Set_Input(U32BIT(GPIO_USER_KEY),0);
		//PIN_Pullup_Enable(T_QFN_48, U32BIT(GPIO_USER_KEY));		//����Ϊ����ģʽ
		//delay_ms(10);
		
		if(BatValue < BATTERY_LOW_LEVEL)						//<2.3V
		{
			//�������(ϵͳ������ָʾ��)
			RedLed_Config(RED_FAST_FLASH_1);
			OS_timer_SetEvt(EVT_REDLED_CONTROL);
			
			//6���޶����Զ�����
			OS_timer_start( EVT_ENTER_SLEEP, 600, false );		// 6s�޶����Զ�����
			WakeupSource = 0;
			//return;
		}
		else
		{
			if(WakeupSource&USER_BUTTON)			//��������
			{
				WakeupSource ^= USER_BUTTON;
				#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
				dbg_printf("Wakeup from power-key\r\n");
				#endif
				start_tx = 0;		
				UserEncryptInit();
				
				key_enable = 1;		//�������ʹ��

			  //SPCStart();
				
				Systerm_States |= STARTUP;

				
				//�̰������������콻����˸(�㲥��ʾ) 1S��˸һ��
				if(0==(Systerm_States&POWERON))
				{
					if(GPIO_Pin_Read(U32BIT(GPIO_USER_KEY)))
					{
						RedLed_Config(RED_SLOW_FLASH_1);
						OS_timer_SetEvt(EVT_REDLED_CONTROL);

						Systerm_States |= POWERON;
						OS_timer_start( EVT_START_DEVICE, 2, false );		//�����豸�������㲥
						
						dbg_printf("SystemState:0x%02x\r\n", Systerm_States);
					}
							
				}
			
			}
			else if(WakeupSource&lock_cut){
				WakeupSource ^= lock_cut;

				timerSendFlag=0;//YCY:260101
				
				start_tx = 0;		
				UserEncryptInit();
				
				key_enable = 1;		//�������ʹ��				
				Systerm_States |= STARTUP;

				if(GPIO_Pin_Read(U32BIT(lock_hall)))//
				{

					RedLed_Config(RED_SLOW_FLASH_1);
					OS_timer_SetEvt(EVT_REDLED_CONTROL);

					Systerm_States |= POWERON;
					OS_timer_start( EVT_START_DEVICE, 2, false );		//�����豸�������㲥
					flg_cutup = 1;
				}
				else{
					OS_timer_start(EVT_ENTER_SLEEP, 1, 0);
				}			
			}
//			else if(WakeupSource&LOCK_CHECK)	//��������
//			{
//				WakeupSource ^= LOCK_CHECK;

//				if(0==key_state_lock)		//����ǿ�����״̬
//				{
//					if(1==GetInputStopR())
//					{
//						Systerm_States |= AUTOLOCK;
//						#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
//						dbg_printf("Wakeup from autolock\r\n");
//						#endif
//						OS_timer_start(EVT_AUTO_LOCK, 2, false);				//20mS ���Զ�ֹͣ
//						key_state_lock = 1;
//					}
//				}
//			}
			else if(WakeupSource&RTC_ALARM)	//RTC����
			{
				WakeupSource ^= RTC_ALARM;
				
				key_enable = 1;		//�������ʹ��
				
				if(0==(Systerm_States&BATDISCHG))	//
				{
					Systerm_States |= BATDISCHG;			//ϵͳ״̬
					BatDischargeOn();								//��ʼ�ŵ�
					#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
					dbg_printf("Wakeup from RTC\r\n");
					#endif
					// OS_timer_start( EVT_BAT_DISCHARGE, 12000, false );	//120s��ֹͣ
							OS_timer_start( EVT_BAT_DISCHARGE, 3000, false );	//30s��ֹͣ			
					//OS_timer_start( EVT_BAT_DISCHARGE, 1000, false );	//�ŵ�10s��ֹͣ (20����һ��)
				}
			}
			else if(WakeupSource & RTC_4Greport){
				WakeupSource ^= RTC_4Greport;
				
				key_enable = 1;		//�������ʹ��	
				Systerm_States |= STARTUP;
				flg_cmd_gpsdata = 1;

				// if(flg_4g_EN==0){//ǿ�ƿ�4G
					RedLed_Config(RED_LED_OFF);					
					lora_e220_init();timerSendFlag=1;//YCY:260101
					UartEn(true);	
					OS_timer_stop(EVT_ENTER_SLEEP);
					OS_timer_start( EVT_START_DEVICE, 2, false );		//�����豸�������㲥 ��ҪΪ��ʱ60�������
				// }						
							
			}
			else		//�������
			{
				WakeupSource = 0;
			}
					
		}
	}
	
	if(key_enable)
	{
		if(0==GPIO_Pin_Read(U32BIT(GPIO_USER_KEY)))		//���������� 
		{
			if(!key_lock)
			{
				key_state = KEY_DOWN;		//����
			}
			if(key_delay_cnt>=200+400)		//2S+4s����
			{
				_user.key_7cnt = 0;
				_user.key_7delay = 0;

				key_delay_cnt = 0;
				key_lock = 1;
				key_state = KEY_UP;		//����
				
				// delay_ms(100);
				// SystemReset();		//��������
				// delay_ms(1000);
				if(flg_4g_EN==0){
					Systerm_States |= STARTUP;	//				
					RedLed_Config(RED_LED_OFF);					
					lora_e220_init();
					UartEn(true);	
					OS_timer_stop(EVT_ENTER_SLEEP);
					OS_timer_start( EVT_START_DEVICE, 2, false );		//�����豸�������㲥 ��ҪΪ��ʱ60�������
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
						// SystemReset();		//����
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
					// 	lora_e220_init();
					// 	UartEn(true);	
					// }		

					//�̰������������콻����˸(�㲥��ʾ) 1S��˸һ��
					RedLed_Config(RED_SLOW_FLASH_1);
					OS_timer_SetEvt(EVT_REDLED_CONTROL);
					flg_needred3 = 1;
					
					Systerm_States |= POWERON;
					OS_timer_start( EVT_START_DEVICE, 2, false );		//�����豸�������㲥
				}
			}
			key_state = KEY_UP;
			key_delay_cnt = 0;
			key_lock = 0;
		}
		
		if((0==GPIO_Pin_Read(U32BIT(STATE_KEY))) && (GPIO_Pin_Read(U32BIT(lock_hall)) == 0))		//��״̬����
		{
			uint8_t databuf[20],datalen=0;
			
			if(StateKeyState==KEY_UP)//��һ�ΰ���
			{	
				if(lora_ctrl.state == LORA_STATE_READY){//4G��ִ���Զ���������
					auto_close();
				}			
				else{		
					if(ConnectPasswordCorrect)		//������ȷ
					{
						lock_state = GetDoorState();	//��ȡ��״̬
						_user.bat_volt = BatValue = GetBatCapacity();	//��ȡ����
						
						databuf[0] = 0xAA;
						cmd_id++;
						databuf[1] = cmd_id;				//����id
						databuf[2] = 0x40;
						databuf[3] = BatValue;			//����
						databuf[4] = lock_state;		//����
						databuf[5] = 0x01;					//���˶�����
						
						databuf[6] = computer_sum(databuf+1,5);
						datalen = 7; 
						
						user_encrypt_notify(aes_key2, databuf, datalen);	//�ϱ�����
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


/* ���Ѱ����ص����� */
void  GPIO_callback(void)
{
	uint32_t SW;

	SW=GPIO_IRQ_CTRL->GPIO_INT; 

//	if(SW&U32BIT(STATE_KEY))
//	{
//		#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
//		dbg_printf("STATE_KEY int\r\n");
//		#endif

//		//WakeupSource &= ~USER_BUTTON;
//		WakeupSource |= LOCK_CHECK;
//	}

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


/* ����sleepǰ���û���Դ */
void WakupConfig_BeforeSleep(void)
{
	struct gap_wakeup_config pw_cfg;
	uint32_t io_int=0,io_inv=0;

SLEEPCONFIG:
		wdt_clear();
		PIN_Pullup_Enable(T_QFN_48, U32BIT(GPIO_USER_KEY)|U32BIT(STATE_KEY));			//����Ϊ����ģʽ
		GPIO_Set_Input(U32BIT(GPIO_USER_KEY)|U32BIT(STATE_KEY), 0);								//����

//ע�⣺���������˯��֮ǰ���뱣֤��Ϊ����Դ��GPIO�ܽŶ�MCU�����ǵ͵�ƽ��Ҳ����˵�����˯�ߵ�ʱ����ԴGPIO�ⲿ״̬Ϊ�ߵ�ƽ��
//����ͱ���Ҫ������gpioģ������ķ�������Ҳ���� PIN_CONFIG->PIN_13_POL�Ĵ���������оƬ���ܹ���ȷ����˯�ߣ�

		PIN_Pullup_Disable(T_QFN_48, U32BIT(lock_hall));	

		if(GPIO_Pin_Read(U32BIT(lock_hall)))
		{
			GPIO_Set_Input(U32BIT(lock_hall), U32BIT(lock_hall));			//����
		}
		else
		{
			GPIO_Set_Input(U32BIT(lock_hall), 0);							//����			
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
	
		if(!GetInputStopF() && GetInputStopR())					//��������״̬�£�StopF=0;StopR=1
		{
			PIN_Pullup_Enable(T_QFN_48, U32BIT(STOP_KEY2));
			PIN_Pullup_Disable(T_QFN_48, U32BIT(STOP_KEY1));
			
			GPIO_Set_Input(io_int, io_inv);		//����
			delay_ms(2);
			
			if(GPIO_Pin_Read(U32BIT(GPIO_USER_KEY))) 
			{
				goto SLEEPCONFIG;
			}
		}
		else if(GetInputStopF() && !GetInputStopR()) 		//��������״̬�£���ֹio�ж�
		{
			PIN_Pullup_Enable(T_QFN_48, U32BIT(STOP_KEY1));
			PIN_Pullup_Disable(T_QFN_48, U32BIT(STOP_KEY2));
			
			GPIO_Set_Input(io_int, io_inv);		//����
			delay_ms(2);

			if(GPIO_Pin_Read(U32BIT(GPIO_USER_KEY))) 
			{
				goto SLEEPCONFIG;
			}
		}
		else if(GetInputStopF() && GetInputStopR())			//��״̬�£���ת����StopF=1;StopR=1
		{
			PIN_Pullup_Enable(T_QFN_48, U32BIT(STOP_KEY2));
			PIN_Pullup_Enable(T_QFN_48, U32BIT(STOP_KEY1));
			
			GPIO_Set_Input(io_int, io_inv);		//����
			delay_ms(2);
			
			if(GPIO_Pin_Read(U32BIT(GPIO_USER_KEY))) 
			{
				goto SLEEPCONFIG;
			}
		}
		else if(!GetInputStopF() && !GetInputStopR())																					//����ֹת���س����������
		{
			PIN_Pullup_Disable(T_QFN_48, U32BIT(STOP_KEY2));
			PIN_Pullup_Disable(T_QFN_48, U32BIT(STOP_KEY1));
			
			GPIO_Set_Input(io_int, io_inv);		//����
			delay_ms(2);
			
			if(GPIO_Pin_Read(U32BIT(GPIO_USER_KEY))) 
			{
				goto SLEEPCONFIG;
			}
		}
		
		if(flg_do_closelockOK){//�������ſ���hall�ж�
		 	io_int |= U32BIT(lock_hall);
		}
	
		pw_cfg.wakeup_type = SLEEP_WAKEUP;
		pw_cfg.wdt_wakeup_en = (bool)false;
		pw_cfg.rtc_wakeup_en = (bool)true;
		pw_cfg.timer_wakeup_en = (bool)false;
		pw_cfg.gpi_wakeup_en = (bool)true;
		pw_cfg.gpi_wakeup_cfg = WAKEUP_PIN;		//�жϻ���pin

		if(flg_do_closelockOK){//�������ſ���hall�ж�
			pw_cfg.gpi_wakeup_cfg |= WAKEUP_hall;
		}
		WakeupConfig(&pw_cfg);
		delay_ms(5);
		//����жϱ�־λ
		GPIO_IRQ_CTRL->GPIO_INT_CLR = 0xFFFFFFFF;
		

		io_irq_enable(io_int, GPIO_callback);	//ʹ�ܰ����ж�
}

/* ���Գ��� */
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


/* ��Ҫ���� */
void user_task(void)
{
	/* ��ʼ�����衢���������㲥 */
	if(TIMER_EVENT & EVT_START_DEVICE)
	{	

		if(flg_4g_EN==0){
			if(!ADV_Flag)
			{
				rf_restart();	//��ʼ�㲥
				
				#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
				dbg_printf("\r\nStartAdv\r\n");
				#endif
				
				ADV_Flag = 1;	//���
			}
		}
		else{//4G�¹������㲥
			rf_stop();
			ADV_Flag= 0;
		}
		
		// 61S�� ����������ӣ����������
		if(flg_4g_EN==0){
			if(flg_cutup==1){
				flg_cutup = 0;
				OS_timer_start(EVT_ENTER_SLEEP, 400, false);//4��
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
	
	/* ֹͣ���й��ܽ���ػ�ģʽ */
	if(flg_err_cutalarm==0){
		if(TIMER_EVENT & EVT_ENTER_SLEEP)
		{
			TIMER_EVENT ^= EVT_ENTER_SLEEP;
			
			#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
			dbg_printf("\r\nbefore enter sleep...\r\n");
			#endif
				
			//����������ӣ���Ͽ�����
			if(connect_flag)
			{
				#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
				dbg_printf("\r\nDisconnect...\r\n");
				#endif
				DisConnect();
			}
			
			//ֹͣ�㲥
			if(ADV_Flag&&!connect_flag)
			{
				ADV_Flag = 0;
				rf_stop();
				#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
				dbg_printf("\r\nStopAdv\r\n");
				#endif
			}
			
			//�ر����е�led��
			AllLedOff();

			lora_e220_deinit(); // Power off LoRa	
			flg_4g_EN = 0;//�������ܹص�4G��
			lora_ctrl.state = LORA_STATE_IDLE;//������   �����Է����Զ���������
		  //wdt_disable();//YCY
			// if(flg_needred3){
			// 	flg_needred3 = 0;
			// 	redledblink3();
			// }

			start_tx = 0;		//���Ϸ���Notify
				
			lock_state = GetDoorState();
			
			if(lock_state == 0)	//���պ�״̬����Ӧ��������ʹ��
			{
				#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
				dbg_printf("lock:CLOSE\r\n");
				#endif
			}
			else							//����״̬�������ֶ���������ʹ��
			{
				#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
				dbg_printf("lock:OPEN\r\n");
				#endif
			}
			
			if(!connect_flag)
			{
				if((Systerm_States&BATDISCHG) || (Systerm_States&AUTOLOCK))
				{
					OS_timer_start(EVT_ENTER_SLEEP, 100, 0);	//1s ���Զ���������
				}
				else
				{
					key_enable = 0;
					
					Disable_Timer2_2ms();	//ֹͣTimer2 10ms��ʱ
					
					ADV_Flag = 0;
				
					Stop_Timer_All();
					
//					RTC_EVT_Start(RTCEVT_72h);	//�����ŵ��¼�
//					RTC_EVT_Start(RTCEVT_24h);	//�����Զ��ϱ�����		

					#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
					dbg_printf("\r\nenter sleep!!!\r\n");
					#endif
					
					WakupConfig_BeforeSleep();			//���û���Դ			
					
	//				if(Systerm_States & OTAMODE)
	//				{
	//					dbg_printf("\r\nSystem Reset!!!\r\n");
	//					DelayMS(5000);
	//					SystemReset();
	//					DelayMS(5000);
	//				}					

					Systerm_States=SLEEP;
				}
				
				
				//SystemSleep();				//ϵͳ˯��
			}
			else
			{
				connect_flag = 0;
				OS_timer_start(EVT_ENTER_SLEEP, 200, 0);	//1s ���Զ���������
			}	
		}
	}
	
	
	/* �Զ��������� */
	if(TIMER_EVENT & EVT_AUTO_LOCK)
	{
		#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
		dbg_printf("\r\nlock on...\r\n");
		#endif
		
		_user.bat_volt = BatValue = GetBatCapacity();	//��ȡ����
		
		if(BatValue >=	BATTERY_LOW_LEVEL)
		{
			if(1==GetInputStopR())					//������ǿ��� 
			{
				MotorStop();
				MotorStartReverse(); 				//�������ﷴת
				OS_timer_start(EVT_MOTOR_STOP, 200, 0);	//5S ���Զ�ֹͣ
				
				#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
					dbg_printf("stopkey check\r\n");
				#endif
				
				OS_timer_SetEvt( EVT_STATE_CHECK );		//������״̬����¼�
			}
			else
			{
				key_state_lock = 0;
				if(Systerm_States&AUTOLOCK)
					Systerm_States ^= AUTOLOCK;
				//OS_timer_start(EVT_MOTOR_STOP, 2, 0);	//20mS ���Զ�ֹͣ
			}
		}
		else
		{
			BlueLed_Config(BLUE_LED_OFF);
			OS_timer_SetEvt(EVT_BLUELED_CONTROL);
			
			RedLed_Config(RED_FAST_FLASH_1);
			OS_timer_SetEvt(EVT_REDLED_CONTROL);
			
			OS_timer_stop(EVT_ENTER_SLEEP);
			OS_timer_start(EVT_ENTER_SLEEP, 600, 0);	//6S ���Զ�ֹͣ
		}
	
		TIMER_EVENT ^= EVT_AUTO_LOCK;
	}
	
	
	/* ����ֹͣ */
	if(TIMER_EVENT & EVT_MOTOR_STOP)
	{
		uint8_t databuf[20],datalen=0;
		
		#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
		dbg_printf("\r\nmotor stop...\r\n");
		#endif
		
		MotorStop();
		
		lock_state = GetDoorState();	//��ȡ��״̬
		_user.bat_volt = BatValue = GetBatCapacity();	//��ȡ����
		
		if(lock_state)
		{
			if(LockOpen_Report)	//����ǿ�������
			{
				RedLed_Config(RED_THREE_FLASH_1);						
				OS_timer_SetEvt(EVT_REDLED_CONTROL);
			}
		}
		else
		{
//			if(0==ConnectPasswordCorrect)		//
//			{
				BlueLed_Config(BLUE_SLOW_FLASH_ONE_1);						
				OS_timer_SetEvt(EVT_BLUELED_CONTROL);
//			}
		}
		
		#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
		dbg_printf("report state...\r\n");
		#endif
		
		if(LockOpen_Report)
		{
			//��������״̬
			databuf[0] = 0xAA;
			cmd_id ++;
			databuf[1] = cmd_id;				//����id
			databuf[2] = 0x30;
			databuf[3] = 0x00;
			databuf[4] = BatValue;			//��ص���
			databuf[5] = lock_state;		//��״̬		
			databuf[6] = 0x00;					//��غ�		
			databuf[7] = computer_sum(databuf+1,6);
			datalen = 8; 
			//LockOpen_Report = 0;
		}
		else
		{
//			databuf[0] = 0xAA;
//			cmd_id++;
//			databuf[1] = cmd_id;				//����id
//			databuf[2] = 0x40;
//			databuf[3] = BatValue;			//����
//			databuf[4] = lock_state;		//����
//			databuf[5] = 0x00;					//��غ�
//			
//			databuf[6] = computer_sum(databuf+1,5);
//			datalen = 7; 
			databuf[0] = 0xAA;
			cmd_id ++;
			databuf[1] = cmd_id;				//����id
			databuf[2] = 0x31;
			databuf[3] = 0x00;
			databuf[4] = BatValue;			//��ص���
			databuf[5] = lock_state;		//��״̬
			databuf[6] = 0x00;					//��غ�		
			databuf[7] = computer_sum(databuf+1,6);
			datalen = 8; 
			LockOpen_Report = 0;
		}
		if(ConnectPasswordCorrect)		//������ȷ
		{
			user_encrypt_notify(aes_key2, databuf, datalen);	//�ϱ�����
		}
		
		if(Systerm_States&AUTOLOCK)	 //�����������
		{
			Systerm_States ^= AUTOLOCK;
			
			key_state_lock = 0;
			flg_openlock = 0;
			
//			if(0==(Systerm_States&POWERON))
//			{
				if(flg_4g_EN){
					flg_cmd_gpsdata = 1;
					OS_timer_stop(EVT_ENTER_SLEEP);	//
					OS_timer_start(EVT_ENTER_SLEEP, 400, 0);	// 4��
				}
				else{
					OS_timer_stop(EVT_ENTER_SLEEP);	//
					OS_timer_start(EVT_ENTER_SLEEP, 200, 0);	//4S ���������
				}
//			}
		}
		else//�����������
		{

			if(flg_openlock){
				flg_openlock = 0;
				if(flg_4g_EN){
					OS_timer_stop(EVT_ENTER_SLEEP);	//
					OS_timer_start(EVT_ENTER_SLEEP, 6000, 0);	// 6000*2 120s���������
				}
			}	
			else{		
				OS_timer_stop(EVT_ENTER_SLEEP);	//
				OS_timer_start(EVT_ENTER_SLEEP, 200, 0);	//4S ���������
			}
		}
		
		TIMER_EVENT ^= EVT_MOTOR_STOP;
	}
	
	
	/* ���72Сʱ�ŵ�һ�� */
	if(TIMER_EVENT & EVT_BAT_DISCHARGE)
	{
		dischrg_flag = 0;
		BatDischargeOff();		//�رշŵ�
		
		if(Systerm_States & BATDISCHG)
		{
			Systerm_States ^= BATDISCHG;	//���
		}
		if(0==(Systerm_States & POWERON))
		{
			OS_timer_stop(EVT_ENTER_SLEEP);
			OS_timer_start(EVT_ENTER_SLEEP, 5, 0);	//50mS ���������
		}
		
		TIMER_EVENT ^= EVT_BAT_DISCHARGE;
	}
	
	/* ��ƿ��� */
	if(TIMER_EVENT & EVT_REDLED_CONTROL)
	{
		RedLedEventHandle();
		TIMER_EVENT ^= EVT_REDLED_CONTROL;
	}
	
	/* ���ƿ��� */
	if(TIMER_EVENT & EVT_BLUELED_CONTROL)
	{
		BlueLedEventHandle();
		TIMER_EVENT ^= EVT_BLUELED_CONTROL;
	}
	
	/* ����/��״̬���(��������ʱ���ϼ��״̬) */
	if(TIMER_EVENT & EVT_STATE_CHECK)
	{
		
		OS_timer_start(EVT_STATE_CHECK, 2, 0);
		
		if(MotorState==MOTOR_FORWARD)		//���ﴦ����ת ����
		{
			if(0 == GetInputStopF())	//��⵽��ת ֹͣλΪ"0"
			{
				//SystemParameter.lockstate = GetDoorState();
				OS_timer_stop(EVT_STATE_CHECK);	//ֹͣ��ⶨʱ��
				//OS_timer_SetEvt( EVT_MOTOR_STOP );		//��������ͣת�¼�
				OS_timer_stop(EVT_MOTOR_STOP);				//ֹͣ����stop��ʱ��
				OS_timer_start(EVT_MOTOR_STOP, 5, 0);
				//led_set_status(BLUE_THREE_FLASH_1);		//������������˸���� Լ550ms
			}
		}
		else if(MotorState==MOTOR_REVERSE)//���ﴦ�ڷ�ת
		{
			if(0 == GetInputStopR())	//��⵽��ת ֹͣλΪ"0"
			{
				//SystemParameter.lockstate = GetDoorState();
				OS_timer_stop(EVT_STATE_CHECK);				//ֹͣ���
				//OS_timer_SetEvt( EVT_MOTOR_STOP );		//��������ͣת�¼�
				OS_timer_stop(EVT_MOTOR_STOP);				//ֹͣ����stop��ʱ��	
				OS_timer_start(EVT_MOTOR_STOP, 2, 0);
			}
		}
		else		//���ﴦ��ֹͣ
		{
			OS_timer_stop(EVT_STATE_CHECK);	//ֹͣ���
		}
		
		
		TIMER_EVENT ^= EVT_STATE_CHECK;
	}
	
	/* ������ֹͣ */
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


/* �����ֻ������� */
uint8_t gatt_read_AppCB(uint8_t *p_data, uint8_t pdata_len)
{
	uint8_t databuf[16],datalen=0;	//databuf ���ͻ�����,datalen �������ݳ���
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
		//��һ���ֽڱ�ʾ��Ч���ݳ���
		len = aes_buff[1];
		memcpy(p_value, aes_buff+2, len);	//
		
	}
	else
	{
		return 1;
	}
	
	if(GetConnectTimeSuccess==true)				//���յ���app��������ʱ��
	{
		if(ConnectPasswordCorrect==true)		//�������������ȷ
		{
			if(len==4)			//ǿ����������
			{
				//BatValue = GetBatCapacity();
				if((p_value[0] == 0x55) && (p_value[2] == 0x50) && (p_value[3] == computer_sum(p_value+1,2)))
				{
					_user.bat_volt =  BatValue = GetBatCapacity();
						
					databuf[0] = 0xAA;
					cmd_id ++;
					databuf[1] = cmd_id;				//����id
					databuf[2] = 0x50;
					databuf[3] = BatValue;			//����
					databuf[4] = lock_state;		//����				
					databuf[5] = 0;							//��غ�
					
					databuf[6] = computer_sum(databuf+1,5);
					datalen = 7; 
					
					#ifdef DEBUGEN
					co_printf("Send Command:\r\n");
					show_reg(databuf, datalen, 1);
					#endif
					
					user_encrypt_notify(aes_key2, databuf, datalen);		//���ܺ���
				}
			}
			else if(len==5)	//�������� ��ѯ���� ������������
			{
				_user.bat_volt = BatValue = GetBatCapacity();
				
				if((p_value[0] == 0x55) && (p_value[4] == computer_sum(p_value+1,3)))
				{
					// 0x21 removed from len==5 branch (moved to len==10 branch below)
					if(p_value[2] == 0x30)		//��������
					{
						LockOpen_Report = 1;		//�����ظ�����
						
						_user.bat_volt = BatValue = GetBatCapacity();
						
						OS_timer_stop(EVT_ENTER_SLEEP);
						
						BlueLed_Config(BLUE_LED_OFF);
						OS_timer_SetEvt(EVT_BLUELED_CONTROL);

						//��ص�������Ż���������
						if(BatValue >= BATTERY_LOW_LEVEL)
						{
							//OS_timer_start(EVT_MOTOR_STOP, 500, false);	//5S ���Զ�ֹͣ			
							if(1==GetInputStopF())
							{
								MotorStop();
								MotorStartForward();
								OS_timer_start(EVT_MOTOR_STOP, 200, false);	//5S ���Զ�ֹͣ
								OS_timer_SetEvt( EVT_STATE_CHECK );					//������״̬����¼�
								OS_timer_start(EVT_ENTER_SLEEP, 3000, false);	//30s
								//flg_openlock = 1;
							}
							else 
							{
								OS_timer_start(EVT_MOTOR_STOP, 2, false);	//20mS ���Զ�ֹͣ
							}
						}
						else
						{
							RedLed_Config(RED_FAST_FLASH_1);
							OS_timer_SetEvt(EVT_REDLED_CONTROL);
							
							OS_timer_start(EVT_ENTER_SLEEP, 600, false);	//6s���������
						}				
					}
					else if(p_value[2] == 0x31)		//��������
					{
						LockOpen_Report = 0;			//�����ظ�����
						
						_user.bat_volt = BatValue = GetBatCapacity();
						
						//CloseCMDReceived = 1;		 //���������Ѿ�����
						
						OS_timer_stop(EVT_ENTER_SLEEP);
						
						//BlueLed_Config(BLUE_LED_OFF);
						//OS_timer_SetEvt(EVT_BLUELED_CONTROL);
						
						

						//��ص�������Ż���������
						if(BatValue >= BATTERY_LOW_LEVEL)
						{
							//OS_timer_start(EVT_MOTOR_STOP, 500, false);	//5S ���Զ�ֹͣ			
							if(0==key_state_lock)		
							{
								if(1==GetInputStopR())	//����ǿ�����״̬
								{
									Systerm_States |= AUTOLOCK;
									#if defined(_DEBUG_) || defined(_SYD_RTT_DEBUG_)
									dbg_printf("Wakeup from autolock\r\n");
									#endif
									OS_timer_start(EVT_AUTO_LOCK, 0, false);	//OS_timer_start(EVT_AUTO_LOCK, 2, false);				//20mS ���Զ�ֹͣ
									key_state_lock = 1;
								}
								else 
								{
									OS_timer_start(EVT_MOTOR_STOP, 2, false);	//20mS ���Զ�ֹͣ
								}
							}
							//OS_timer_start(EVT_ENTER_SLEEP, 2100, false);		//21s���������
						}
						else		//�͵���
						{
							RedLed_Config(RED_FAST_FLASH_1);
							OS_timer_SetEvt(EVT_REDLED_CONTROL);
							
							OS_timer_start(EVT_ENTER_SLEEP, 600, false);	//6s���������
						}				
					}
					else if(p_value[2] == 0x40)		//״̬��ѯ����
					{
						_user.bat_volt = BatValue = GetBatCapacity();
						
						databuf[0] = 0xAA;
						cmd_id ++;
						databuf[1] = cmd_id;				//����id
						databuf[2] = 0x40;
						databuf[3] = BatValue;			//����
						databuf[4] = lock_state;		//����
						databuf[5] = 0x00;					//��غ�
						
						databuf[6] = computer_sum(databuf+1,5);
						datalen = 7; 
						
						#ifdef DEBUGEN
						co_printf("Send Command:\r\n");
						show_reg(databuf, 7, 1);
						#endif
						
						user_encrypt_notify(aes_key2, databuf, datalen);		//���ܺ���
					}
					else if(p_value[2] == 0x41){//��������1��ָ���ѯ���ţ��ش�����
						databuf[0] = 0xAA;
						cmd_id ++;
						databuf[1] = cmd_id;				//����id
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
						user_encrypt_notify(aes_key2, databuf, datalen);		//���ܺ���
					}
				}
			}
			else if(len==10)
			{
				if((p_value[0] == 0x55) && (p_value[2] == 0x21) && (p_value[9] == computer_sum(p_value+1,8)))
				{
					uint32_t newpass = (uint32_t)p_value[3]*100000+(uint32_t)p_value[4]*10000+(uint32_t)p_value[5]*1000+(uint32_t)p_value[6]*100+(uint32_t)p_value[7]*10+(uint32_t)p_value[8];
					SystemParameter.password[0] = (uint8_t)(newpass >> 16);
					SystemParameter.password[1] = (uint8_t)(newpass >> 8);
					SystemParameter.password[2] = (uint8_t)(newpass);
					UpdateFlashParameter();
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
		}
		else 
		{
			if(len==10)
			{
				//���յ�������������
				if((p_value[0] == 0x55) && (p_value[2] == 0x20) && (p_value[9] == computer_sum(p_value+1,8)))
				{
					//cmd_id = p_value[1];
					//�ж������Ƿ���ȷ
					if(p_value[3]==0&&p_value[4]==0&&p_value[5]==0)
					{
						if(p_value[6]==0&&p_value[7]==0&&p_value[8]==0)
						{
							ConnectPasswordCorrect = true;
							
							OS_timer_stop(EVT_ENTER_SLEEP);
							
							databuf[0] = 0xAA;
							cmd_id++;
							databuf[1] = cmd_id;		//����id
							databuf[2] = 0x02;
							databuf[2] = 0x20;
							databuf[3] = 0x00;					//������ȷ
							databuf[4] = computer_sum(databuf+1,3);
							datalen = 5; 
							
							#ifdef DEBUGEN
							co_printf("Send Command:\r\n");
							show_reg(databuf, 5, 1);
							#endif
							
							BlueLed_Config(BLUE_SLOW_FLASH_1);
							OS_timer_SetEvt(EVT_BLUELED_CONTROL);
							
							user_encrypt_notify(aes_key2, databuf, datalen);		//���ܺ���

							OS_timer_start(EVT_ENTER_SLEEP, 6000, false);	//������֤�ɹ���1���Ӻ��޲�����������
						}
						else 
						{
							//������֤����
							databuf[0] = 0xAA;
							cmd_id ++;
							databuf[1] = cmd_id;		//����id
							databuf[2] = 0x02;
							databuf[2] = 0x20;
							databuf[3] = 0x01;					//�������
							databuf[4] = computer_sum(databuf+1,3);
							datalen = 5; 
							
							user_encrypt_notify(aes_key2, databuf, datalen);		//���ܺ���
						}
					}
					else
					{
						//������֤����
						databuf[0] = 0xAA;
						cmd_id ++;
						databuf[1] = cmd_id;		//����id
						databuf[2] = 0x02;
						databuf[2] = 0x20;
						databuf[3] = 0x01;					//�������
						databuf[4] = computer_sum(databuf+1,3);
						datalen = 5; 
						
						#ifdef DEBUGEN
						co_printf("Send Command:\r\n");
						show_reg(databuf, 5, 1);
						#endif
						
						user_encrypt_notify(aes_key2, databuf, datalen);		//���ܺ���
					}
					
				}
				else 
				{
					//���ݼ��ܴ�����Ч����
					
				}
			}

		}
	} 
	else 
	{
		if(len == 10)		//55 cmd_id 0x10 �� �� �� ʱ �� �� sum
		{
			//���ӳɹ�APP��������������豸(����������֤ͨ��)
			if((p_value[0] == 0x55) && (p_value[2] == 0x10) && (p_value[9] == computer_sum(p_value+1,8)))
			{
				GetConnectTimeSuccess = true;	//��֤ͨ����������ʹ����Կkey2
				cmd_id = p_value[1];
				cmd_id++;
				OS_timer_stop(EVT_ENTER_SLEEP);
				
//				RedLed_Config(RED_LED_OFF);
//				OS_timer_SetEvt(EVT_REDLED_CONTROL);		//�ر�led��ʾ
				
				//�ظ�ָ��
				databuf[0] = 0xAA;
				databuf[1] = cmd_id;		//����id
				databuf[2] = 0x10;
				databuf[3] = computer_sum(databuf+1,2);
				datalen = 4; 
				
				user_encrypt_notify(aes_key1, databuf, datalen);		//���ܺ���
				
				aes_key2[10] = p_value[3];	//������Կkey2������Կ1����������ֽ��滻Ϊ�ꡢ�¡��ա�ʱ���֡���
				aes_key2[11] = p_value[4];
				aes_key2[12] = p_value[5];
				aes_key2[13] = p_value[6];	//������Կkey2
				aes_key2[14] = p_value[7];
				aes_key2[15] = p_value[8];

				OS_timer_start(EVT_ENTER_SLEEP, 1000, false);	//���¶�ʱ6s�ȴ���������

			}
			else	//���ݸ�ʽ У�����֤����
			{
//				OS_timer_stop(EVT_ENTER_SLEEP);
//				OS_timer_start(EVT_ENTER_SLEEP, 6000, false);	//���¶�ʱ6s�ȴ���������
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


