package com.liquidum.client.motion;

/**
 * Spring/damped motion (roadmap §7).
 * FPS-independent, uses deltaTime. No bounce by default (critically damped).
 */
public class SpringPhysics {
	public float position;
	public float velocity;
	public float target;
	public float stiffness = 300f;
	public float damping = 30f;
	public float mass = 1f;

	public SpringPhysics(float initial) {
		this.position = initial;
		this.target = initial;
	}

	public void setTarget(float t) {
		this.target = t;
	}

	/** Integrate one frame. */
	public void update(float dt) {
		if (dt <= 0) return;
		dt = Math.min(dt, 0.033f); // clamp to 30 FPS equivalent
		float f = -stiffness * (position - target);
		float d = -damping * velocity;
		float a = (f + d) / mass;
		velocity += a * dt;
		position += velocity * dt;
		// settle
		if (Math.abs(position - target) < 0.001f && Math.abs(velocity) < 0.001f) {
			position = target;
			velocity = 0;
		}
	}

	public boolean isAtRest() {
		return Math.abs(position - target) < 0.001f && Math.abs(velocity) < 0.01f;
	}
}
