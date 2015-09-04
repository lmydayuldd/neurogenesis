package org.thoughtsfactory.neurogenesis;

import org.apache.log4j.Logger;

import repast.simphony.space.continuous.ContinuousSpace;

public class LightSensor {

	
	//
	private final static Logger logger = Logger.getLogger(LightSensor.class);	

	
	private final ContinuousSpace<Object> space;
	
	private final double angularRelativePosition;
	
	private double lightIntensity = 0;
	
	
	/**
	 * 
	 * @param newRobot
	 */
	public LightSensor(final ContinuousSpace<Object> newSpace,
			final double newAngularRelativePosition) {
		
		this.space = newSpace;
		this.angularRelativePosition = newAngularRelativePosition;
		
	} // End of LightSensor()
	
	
	/**
	 * 
	 * @return
	 */
	public double getAngularRelativePosition() {
		return this.angularRelativePosition;
	}
	
	
	/**
	 * 
	 * @return
	 */
	public double getLightIntensity() {
		return this.lightIntensity;
	}
	
	
	/**
	 * 
	 * @return
	 */
	public String getLabel() {
		return this.angularRelativePosition > 0 ? "Left" : "Right";
	}
	
	
	/**
	 * 
	 */
	public void update(final Robot robot, final double newLightIntensity) {
		
		this.space.moveTo(this, 
				robot.getRadius() * Math.cos(robot.getAngularPosition(this)), 
				robot.getRadius() * Math.sin(robot.getAngularPosition(this)));
		logger.debug("Updating sensor with intensity " + newLightIntensity);
		this.lightIntensity = newLightIntensity;
		
	} // End of update()
	
	
} // End of LightSensor class