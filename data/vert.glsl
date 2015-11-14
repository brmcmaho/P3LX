#version 150

uniform mat4 modelview;
uniform mat4 projection;

attribute vec4 vertex;
attribute vec4 color;

varying vec4 vertexColor;

uniform vec3 attenuation;
uniform float pointSize;

void main() {
  vec4 cameraCoord = modelview * vertex; 
  gl_Position = projection * cameraCoord;
  float distance = distance(cameraCoord, vec4(0., 0., 0., 1.));
  float att = inversesqrt(
  	attenuation.x + distance * (attenuation.y + distance * attenuation.z)
  );
  gl_PointSize = max(pointSize * att, 1.);
  vertexColor = color;
}
