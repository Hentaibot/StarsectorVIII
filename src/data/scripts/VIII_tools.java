package data.scripts;

import com.fs.starfarer.api.Global;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

public class VIII_tools {
    public VIII_tools(){
    }
    public static boolean ifBoxUnitLoad() {
        return Global.getSettings().getModManager().isModEnabled("BoxUtil");
    }

    public static int createShader(String source, int shaderType) {
        if (!GLContext.getCapabilities().OpenGL20) {
            Global.getLogger(Global.class).info("error1_1");
            return 0;
        } else {
            int shaderID = GL20.glCreateShader(shaderType);
            GL20.glShaderSource(shaderID, source);
            GL20.glCompileShader(shaderID);
            if (GL20.glGetShaderi(shaderID, 35713) == 0) {
                Global.getLogger(Global.class).info("error1_2 : " + GL20.glGetShaderInfoLog(shaderID, GL20.glGetShaderi(shaderID, 35716)));
                GL20.glDeleteShader(shaderID);
                return 0;
            } else {
                Global.getLogger(Global.class).info("error1_3");
                return shaderID;
            }
        }
    }

    public static int createShaderProgram(String vertSource, String fragSource) {
        if (!GLContext.getCapabilities().OpenGL20) {
            Global.getLogger(Global.class).info("error2_1");
            return 0;
        } else {
            int programID = GL20.glCreateProgram();
            int[] shaders = new int[]{createShader(vertSource, 35633), createShader(fragSource, 35632)};
            if (shaders[0] != 0 && shaders[1] != 0) {
                GL20.glAttachShader(programID, shaders[0]);
                GL20.glAttachShader(programID, shaders[1]);
                GL20.glLinkProgram(programID);
                if (GL20.glGetShaderi(programID, 35713) == 0) {
                    Global.getLogger(Global.class).info("error2_2");
                    GL20.glDeleteProgram(programID);
                    GL20.glDetachShader(programID, shaders[0]);
                    GL20.glDeleteShader(shaders[0]);
                    GL20.glDetachShader(programID, shaders[1]);
                    GL20.glDeleteShader(shaders[1]);
                    return 0;
                } else {
                    Global.getLogger(Global.class).info("error2_3");
                    return programID;
                }
            } else {
                return 0;
            }
        }
        }
}