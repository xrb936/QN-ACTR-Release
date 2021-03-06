package qnactr.objectDesigner;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JOptionPane;

import qnactr.sim.GlobalUtilities;
import qnactr.sim.QnactrSimulation;
import jmt.engine.simEngine.SimSystem;


public class World3D_Template_Driving_Method {

  public QnactrSimulation sim;
	public double Accel_Factor_Dthw;
	public double Accel_Factor_Thw;
	public double Accelbrake;
	public double Accelbrake_Abs_Max;
	public double Accelbrake_Delay_With_Foot_Move;
	public double Accelbrake_Delay_Without_Foot_Move;
	public String Accelbrake_Foot_On;
	public double Auto_Speed;
	public double DriverCar_Road_Surface_Friction_Coeff;
	public String DriverCar_World3D_ID;
	public double Far_Point_Time_Ahead;
	public double Near_Angle_Abs_Max;
	public double Near_Point_Distance;
	public String Obsolete_Driver_Start_On_Road_Name;
	public String Obsolete_Driver_Start_On_Road_World3D_ID;
	public ArrayList<String> OtherCar_World3D_ID = new ArrayList<String>();
	public double Perfect_Machine_Driving_At_Speed;
	public double Standard_Gravity;
	public double Steer_Factor_Delta_Far_Angle;
	public double Steer_Factor_Delta_Near_Angle;
	public double Steer_Factor_Near_Angle;
	public double Time_Head_Way_Follow;
	public double Time_Head_Way_Max;
	public String Visual_Attention_Location_World3D_ID;
	public String Who_Drive;
	private Torcs_Percept torcsPerceptEarly;
	private Torcs_Percept torcsPerceptLate;	
	//public double Torcs_Cycle_Time;
	public double torcsControlAccelerator = 0.0; //0-1
	public double torcsControlBrake = 0.0; //0-1
	public double torcsControlSteerAngleDegree = 0.0; //

	
	private int UDPQNtoTORCSPort = 5678; 	//this can be set in TORCS expconfig.txt
	private int UDPTORCStoQNPort = 8765;	//this can be set in TORCS expconfig.txt
	private final int bufferSizetoTORCS = 500; 		//this can be set in TORCS human.cpp
	private final int bufferSizefromTORCS = 500;	//this can be set in TORCS human.cpp
	private DatagramSocket sendSocket;
	private DatagramSocket receiveSocket;
	
	public  World3D_Template_Driving_Method(QnactrSimulation Sim){
		sim = Sim;
		
		Accelbrake=0.0F; //[-1, 1]  -1 for full brake, 1 for full accelerator, 0 for foot in air
		Accelbrake_Abs_Max=1.0F; // set the absolute max of accel and brake values. normally [-1, 1]
		Accelbrake_Delay_With_Foot_Move = 0.7F;  //in second (defparameter *accelbrake-delay-with-foot* .700. 		  "Includes 200ms preparation (style, foot, r, theta). 		   and 500ms execution (from J.D.Lee et al., 2002, Human Factors).")
		Accelbrake_Delay_Without_Foot_Move=0.0F; // in second, 0 by default. 		(defparameter *accelbrake-delay* 0)
		Accelbrake_Foot_On="accel"; //accel or brake
		Auto_Speed=0.0F; // only for the old built in 3D model
		DriverCar_Road_Surface_Friction_Coeff=0.2F; //only for the old built in 3D model. (f-surface-friction .2)
		Far_Point_Time_Ahead=2.0F; // (defparameter *far-time* 2)  ;;originally 4.0 in Salvucci's model code his paper (2006) use 2.0.	Used in Salvucci's model to compute the Time Head Way (s) of the On-Road alternative of far point. then * speed to get On-Road far point ahead distance (m)
		Near_Angle_Abs_Max=4.0F; //��nmax 0.07 rad Estimated in Salvucci (2006). 0.07 rad / PI * 180 = 4 degrees. 
		Near_Point_Distance=10.0F; //near point distance ahead on the road. 	10 m by default, as in Salvucci 2006
		Perfect_Machine_Driving_At_Speed=0.0F; //only for the old built in 3D model
		Standard_Gravity=9.8F; //only for the old built in 3D model
		
		//steering control equation main parameters
//		Salvucci (2006) used:
//		K far  =  16.0
//		K near = 4.0
//		K i = 3.0
		Steer_Factor_Delta_Far_Angle=16.0F;   // K far in Salvucci (2006)  Delta steer angle = K far * Delta far angle  +  K near * Delta near angle  + K i * MIN (Near angle , Near_Angle_Abs_Max )  * Delta t
		Steer_Factor_Delta_Near_Angle=4.0F;
		Steer_Factor_Near_Angle=3.0F;
		
		
		//speed control equation main parameters
		Accel_Factor_Dthw=3.0F;  //K car in Salvucci (2006)  Delta_Phi = K car * Delta thw car  +  K follow * (thw car - thw  follow ) * Delta t.  Salvucci: set at 3.0,  Method: informal
		Accel_Factor_Thw=1.0F;  // K follow in Salvucci (2006)  Delta_Phi = K car * Delta thw car  +  K follow * (thw car - thw  follow ) * Delta t. 	(defparameter *accel-factor-thw* 1). 	set at 1.0,  Method: informal
		Time_Head_Way_Follow=1.0F; ////(defparameter *thw-follow* 1.0) Used in Salvucci 2006  Accel brake control function "thw follow":

		
		//Steer_Factor_Scale
		//in Salvucci (2009) rapid prototyping, they used a scale factor that can be multiplied to each steering parameters. "the steering factor scales the default values of all three model parameters (knear=3.4,kfar =13.6,kI =2.55), and adjustment of this value in a principled manner allows users to produce better quantitative fits to empirical results if desired" (where steering factor = 0.85)
		
		Time_Head_Way_Max=4.0F; // s, 4.0 as in Salvucci's model
		//When far point ahead distance > 0 m, but speed is 0.0 (this cannot be On-Road far point cases), use Time_Head_Way_Max as the Time Head Way
		//Else if far point true THW is > Time_Head_Way_Max, use Time_Head_Way_Max instead.
		//Time Head Way	is    thw car   in the function below, also thw car old that is used to compute Delta thw car in combination with the car.
		//Delta_Phi = K car * Delta thw car  +  K follow * (thw car - thw  follow ) * Delta t
		
		
		Who_Drive="model";
		torcsPerceptEarly = new Torcs_Percept();
		torcsPerceptLate = new Torcs_Percept();
		//Torcs_Cycle_Time = 0.0;
		
		try {
			sendSocket = new DatagramSocket(16);  //this is to set the socket sending info to TORCS. 
		} catch (SocketException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		try {
			receiveSocket = new DatagramSocket(UDPTORCStoQNPort);
		} catch (SocketException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
				
		
		
	}
	
	public void sendControlToTORCS(){
		
		//debug
		//torcsControlAccelerator = 0.5;
		//		
		
		String str = "QNClock: " + Double.toString( GlobalUtilities.round(SimSystem.clock(),3) ) + ", "; //in second
		str += "Accelerator: " + torcsControlAccelerator + ", ";  //0-1
		str += "Brake: " + torcsControlBrake + ", ";  //0-1
		str += "Steering: " + torcsControlSteerAngleDegree + ", ";  //steering angle in degree

				
		byte buffer[] = new byte[bufferSizetoTORCS];
		buffer = str.getBytes(); 
		
		DatagramPacket packet;
		
		try {
			packet = new DatagramPacket(buffer, buffer.length, InetAddress.getLocalHost(), UDPQNtoTORCSPort);
			sendSocket.send(packet);	
			Thread.sleep(1);			
		} 
		catch (UnknownHostException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}		
		catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} 		
		catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} 
		
	}
	
	public void receivePerceptEarlyFromTORCS(){
		//torcs to qn
		
		byte buffer[] = new byte[bufferSizefromTORCS]; 
		DatagramPacket packet = new DatagramPacket(buffer, buffer.length); 
		try {
			receiveSocket.receive(packet);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} 
				
		String receivedString = new String(packet.getData());
		//System.out.println("receivePerceptEarlyFromTORCS Message received from TORCS: " + receivedString); 
		double TORCSClock = 0.0;
		double nearPointAngleDegree = 0.0;
		double farPointAngleDegree = 0.0;
		double farPointDistanceMeter = 0.0;
		double speedmps = 0.0;
		
		Matcher m1 = Pattern.compile("TORCSClock: [-+]?[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?").matcher(receivedString);
		Matcher m2 = Pattern.compile("farPointDistanceMeter: [-+]?[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?").matcher(receivedString);
		Matcher m3 = Pattern.compile("speedM/s: [-+]?[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?").matcher(receivedString);
		
//		Matcher nearPoint_n1 = Pattern.compile("nearPointAngleDegree-1: [-+]?[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?").matcher(receivedString);
		Matcher nearPoint_0 = Pattern.compile("nearPointAngleDegree0: [-+]?[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?").matcher(receivedString);
		Matcher nearPoint_p1 = Pattern.compile("nearPointAngleDegree-p1: [-+]?[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?").matcher(receivedString);  // note, cannot have + char in the pattern
		
//		Matcher farPoint_n1 = Pattern.compile("farPointAngleDegree-1: [-+]?[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?").matcher(receivedString);
		Matcher farPoint_0 = Pattern.compile("farPointAngleDegree0: [-+]?[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?").matcher(receivedString);
		Matcher farPoint_p1 = Pattern.compile("farPointAngleDegree-p1: [-+]?[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?").matcher(receivedString);  // note, cannot have + char in the pattern
		
		Matcher m_leftObjDist = Pattern.compile("leftObjDist: [-+]?[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?").matcher(receivedString);
		Matcher m_leftMirrorObjDist = Pattern.compile("leftMirrorObjDist: [-+]?[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?").matcher(receivedString);
		Matcher m_rightObjDist = Pattern.compile("rightObjDist: [-+]?[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?").matcher(receivedString);
		Matcher m_rightMirrorObjDist = Pattern.compile("rightMirrorObjDist: [-+]?[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?").matcher(receivedString);

		
	    
	    if( m1.find() ) TORCSClock = Double.parseDouble(m1.group().substring("TORCSClock: ".length()));
	    if( m2.find() ) farPointDistanceMeter = Double.parseDouble(m2.group().substring("farPointDistanceMeter: ".length()));
	    if( m3.find() ) speedmps = Double.parseDouble(m3.group().substring("speedM/s: ".length()));	    
	    torcsPerceptEarly.TORCSClock = TORCSClock;
	    torcsPerceptEarly.farPointDistanceMeter = farPointDistanceMeter;
	    torcsPerceptEarly.speed = speedmps;
	    
	    
//	    if( nearPoint_n1.find() ) torcsPerceptEarly.nearPointAngleDegree.put(-1,  Double.parseDouble(nearPoint_n1.group().substring("nearPointAngleDegree-1: ".length())) );
	    if( nearPoint_0.find() ) torcsPerceptEarly.nearPointAngleDegree.put(0,  Double.parseDouble(nearPoint_0.group().substring("nearPointAngleDegree0: ".length())) );
	    if( nearPoint_p1.find() ) torcsPerceptEarly.nearPointAngleDegree.put(1,  Double.parseDouble(nearPoint_p1.group().substring("nearPointAngleDegree-p1: ".length())) );
	    
//	    if( farPoint_n1.find() ) torcsPerceptEarly.farPointAngleDegree.put(-1, Double.parseDouble(farPoint_n1.group().substring("farPointAngleDegree-1: ".length())));
	    if( farPoint_0.find() ) torcsPerceptEarly.farPointAngleDegree.put(0, Double.parseDouble(farPoint_0.group().substring("farPointAngleDegree0: ".length())));
	    if( farPoint_p1.find() ) torcsPerceptEarly.farPointAngleDegree.put(1, Double.parseDouble(farPoint_p1.group().substring("farPointAngleDegree-p1: ".length())));
	    
	    if( m_leftObjDist.find())   torcsPerceptEarly.leftObjDist =   Double.parseDouble(m_leftObjDist.group().substring("leftObjDist: ".length()));
	    if( m_leftMirrorObjDist.find())   torcsPerceptEarly.leftMirrorObjDist =   Double.parseDouble(m_leftMirrorObjDist.group().substring("leftMirrorObjDist: ".length()));	    
	    if( m_rightObjDist.find())   torcsPerceptEarly.rightObjDist =   Double.parseDouble(m_rightObjDist.group().substring("rightObjDist: ".length()));
	    if( m_rightMirrorObjDist.find())   torcsPerceptEarly.rightMirrorObjDist =   Double.parseDouble(m_rightMirrorObjDist.group().substring("rightMirrorObjDist: ".length()));	    

	    
//	    System.out.println("=========");
//	    System.out.println(torcsPerceptEarly.leftObjDist);    
//	    System.out.println(torcsPerceptEarly.leftMirrorObjDist);
//	    System.out.println(torcsPerceptEarly.rightObjDist);
//	    System.out.println(torcsPerceptEarly.rightMirrorObjDist);

	    

		
	}

	public Torcs_Percept getTorcsPercept(){
		
		return torcsPerceptEarly; // currently try use this, may change it and see what's different
	}

	
	
	
	public void Do_Accelerate ( double far_time_head_way_new, double delta_time_head_way, double delta_time){
	  
	  
	  /*
	(defun do-accelerate (thw dthw dt)
	  (let ((dacc (+ (* dthw *accel-factor-dthw*)
	                 (* dt (- thw *thw-follow*) *accel-factor-thw* ))))
	    (incf *accelbrake* dacc)
	    (setf *accelbrake* (minsigned *accelbrake* *accel-max*))
	    
	        
	    
	    (let ((new-foot-on (if (>= *accelbrake* 0) 'accel 'brake)))
	      (my-queue *accelbrake-queue*
	                (list *accelbrake* new-foot-on)
	                (if (equalp *accelbrake-foot-on* new-foot-on)
	                    *accelbrake-delay*
	                    *accelbrake-delay-with-foot*)))))
	  */
	  
	  //Salvucci (2006)  Delta_Phi = K car * Delta thw car  +  K follow * (thw car - thw  follow ) * Delta t
	  
	  //compute accelbrake
	  double delta_change_of_accelbrake = this.Accel_Factor_Dthw * delta_time_head_way + this.Accel_Factor_Thw * ( far_time_head_way_new - this.Time_Head_Way_Follow) * delta_time;
	  double new_accelbrake = this.Accelbrake + delta_change_of_accelbrake;
	  if( new_accelbrake > this.Accelbrake_Abs_Max ) new_accelbrake = this.Accelbrake_Abs_Max;
	  else if (new_accelbrake < -1.0 * this.Accelbrake_Abs_Max) new_accelbrake = -1.0 * this.Accelbrake_Abs_Max;
	  
	  this.Accelbrake = new_accelbrake;
	  
	  //do accelbrake
	  String new_foot_on;
	  if (new_accelbrake >= 0.0  ) new_foot_on = "accel";
	  else new_foot_on = "brake";
	  double delay;
	  if (this.Accelbrake_Foot_On == new_foot_on) delay = this.Accelbrake_Delay_Without_Foot_Move;
	  else delay = this.Accelbrake_Delay_With_Foot_Move;

	  //Simulation.Model.PrintOutput("far_thw_new: " + far_time_head_way_new + ", delta_thw: " + delta_time_head_way + ", delta_t: " + delta_time + ", delay: " + delay + ", new_accelbrake : " + new_accelbrake );

	    
	  //to start the model from the initial stop condition.
	  //if( SimSystem.clock() < 10.0 && far_time_head_way_new == 0.0 && delta_time_head_way == 0.0){
	  //  new_accelbrake = 0.01;
	    
	  //}
	  
	  
	  if( delay == 0.0) sim.funs.TaskTemplateFun__Update_DriverCar_Accelbrake( new_accelbrake );
	  else if ( delay > 0.0 )  sim.funs.ProgramUtilitiesFun__Delayed_Function_Call_No_Return_Value(delay, "TaskTemplateFun__Update_DriverCar_Accelbrake", new_accelbrake )  ;
	  else {
	    System.out.println("Error! World3D_Template_Driving_Method.Do_Accelerate has delay < 0: " + delay );
	    SimSystem.abort();
	  }
	  
	  
	  //debug
	  //JOptionPane.showMessageDialog(null, "delta_change_of_accelbrake: " + delta_change_of_accelbrake + ", delta_time_head_way: " + delta_time_head_way + ", far_time_head_way_new: " + far_time_head_way_new + ", delta_time: " + delta_time , "delay: " + delay , JOptionPane.INFORMATION_MESSAGE); //QN-Java
	  

	}

	public double Get_Delta_Steer (double near_angle_old, double near_angle_new, double far_angle_old, double far_angle_new, double clock_old, double clock_new ){
	  //all angle in the same unit. (degree by default)
	  
	  /*
	  (let ((dsteer (+ (* dfa *steer-factor-dfa*)
	                   (* dna *steer-factor-dna*)
	                   (* (minsigned na *steer-na-max*) *steer-factor-na* dt))))
	  */
	  double delta_far_angle = far_angle_new - far_angle_old;
	  double delta_near_angle = near_angle_new - near_angle_old;
	  double delta_time = clock_new - clock_old;
	  //Delta steer angle = K far * Delta far angle  +  K near * Delta near angle  + K i * MIN (Near angle , Near_Angle_Abs_Max )  * Delta t
	  double near_angle_used;
	  if (near_angle_new >= 0.0 ) near_angle_used = Math.min( near_angle_new, this.Near_Angle_Abs_Max);
	  else near_angle_used = Math.max( near_angle_new, -1.0 * this.Near_Angle_Abs_Max);
	  double delta_steer_angle = this.Steer_Factor_Delta_Far_Angle * delta_far_angle + this.Steer_Factor_Delta_Near_Angle * delta_near_angle + this.Steer_Factor_Near_Angle * near_angle_used * delta_time;
	  
	  //Simulation.Model.PrintOutput(near_angle_old + " " + near_angle_new + ",  " + far_angle_old + " " + far_angle_new);
	  
	 
	  //reduce delta_steer_angle at the very beginning of simulation, avoid a very big steering 
	  double earlyStartMaxDegree = 5.0;
	  if ( SimSystem.clock() < 1.0 &&  near_angle_old == 0.0 && far_angle_old == 0.0 && Math.abs(delta_steer_angle) > earlyStartMaxDegree ) delta_steer_angle = Math.signum(delta_steer_angle) *  earlyStartMaxDegree;
	  //
	  
	  return delta_steer_angle;
	}

	public double Get_Far_Time_Head_Way ( double far_distance, double speed ) {
  	// in Salvucci's model
  	// (defmacro thw/ (d v) `(if (zerop ,v) *thw-max* (/ ,d ,v)))
	  //	  !bind! =fthw (min (thw/ =fd =v) *thw-max*)
	  if(far_distance == 0.0) return 0.0;
	  
	  double thw;
	  if( speed == 0.0 ) thw = this.Time_Head_Way_Max;
	  else thw = Math.min( far_distance / speed , this.Time_Head_Way_Max );
	    
	  return thw; 
	}

	public double Get_Space_1D_Direct_Speed_Control_Delta_Speed( Chunk the_chunk ) {
	  double Parameter_Min_Target_Destination_Time_Head_Way = 10.0 ;  // second, used to limit the max speed
	  double Speed_Change_Step = 2.0 ; //m/s
	  double delta_speed ;
	  
	  double  target_ahead_distance_old = Double.valueOf( sim.funs.ChunkFun__Get_Chunk_Slot_Value(the_chunk, "para-1") ) ;
	  double  target_ahead_distance_new = Double.valueOf( sim.funs.ChunkFun__Get_Chunk_Slot_Value(the_chunk, "para-2"));
	  double  self_speed_old = Double.valueOf( sim.funs.ChunkFun__Get_Chunk_Slot_Value(the_chunk, "para-3"));
	  double  self_speed_new = Double.valueOf( sim.funs.ChunkFun__Get_Chunk_Slot_Value(the_chunk, "para-4"));
	  double  clock_time_old = Double.valueOf( sim.funs.ChunkFun__Get_Chunk_Slot_Value(the_chunk, "para-5"));
	  double  clock_time_new = Double.valueOf( sim.funs.ChunkFun__Get_Chunk_Slot_Value(the_chunk, "para-6"));
	  
	  //actions depend on conditions.
	  if( Math.abs( target_ahead_distance_new ) < 50 ) { //in the stop zone, change speed towards 0.0.
	    if ( self_speed_new > 0 ) delta_speed = -1.0 * Speed_Change_Step;
	    else if ( self_speed_new < 0 ) delta_speed = 1.0 * Speed_Change_Step;
	    else delta_speed = 0.0;
	  }
	  else{ // out of the stop zone
	    if( self_speed_new == 0.0) { // initial speed = 0.0; or stopped outside of the stop zone, need to restart moving
	      delta_speed = Math.signum(target_ahead_distance_new) * Speed_Change_Step;
	    }
	    else { // self_speed_new != 0.0
	      
	      //double thw_old = target_ahead_distance_old, self_speed_old ); //currently do not need thw_old, if need, think about self_speed_old = 0.0 problem.
	      double thw_new = target_ahead_distance_new / self_speed_new ;
	      
	      
	      //here may test different approximation or estimation or adding noise to represent the judgement of human
	      
	      //this is the round to integer method of approximation
	      double thw_new_approximation = GlobalUtilities.round( thw_new , 0 ); 
	      double Parameter_Min_Target_Destination_Time_Head_Way_approximation = GlobalUtilities.round( Parameter_Min_Target_Destination_Time_Head_Way , 0 );
	        
	      if ( thw_new > 0.0 ){ // still need to go ahead
	        
	        if ( thw_new_approximation > Parameter_Min_Target_Destination_Time_Head_Way_approximation ) delta_speed = 1.0 * Speed_Change_Step;
	        else delta_speed = -1.0 * Speed_Change_Step;
	        
	      }
	      else if (thw_new < 0.0 ){ //need back up
	        if ( Math.abs( thw_new_approximation ) > Parameter_Min_Target_Destination_Time_Head_Way_approximation ) delta_speed = -1.0 * Speed_Change_Step;
	        else delta_speed = 1.0 * Speed_Change_Step;
	      }
	      else delta_speed = 0.0;
	    }
	  }
	  
	  return delta_speed; 
	}
	

	
}
