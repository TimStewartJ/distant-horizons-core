#version 330 core

in vec2 vPosition;

out vec2 texCoord;

// DH vert fade test
void main()
{
    gl_Position = vec4(vPosition, 1.0, 1.0);
    texCoord = vPosition.xy * 0.5 + 0.5;
}