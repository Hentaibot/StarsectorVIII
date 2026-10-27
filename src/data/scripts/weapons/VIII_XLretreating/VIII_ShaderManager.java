//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by Fernflower decompiler)
//

package data.scripts.weapons.VIII_XLretreating;

import com.fs.starfarer.api.Global;
import java.util.HashMap;
import java.util.Map;
import org.apache.log4j.Logger;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

public class VIII_ShaderManager {
    private static VIII_ShaderManager instance;
    private Map<String, Integer> shaders = new HashMap();
    private boolean openGL20Supported = false;
    private static final Logger LOG = Global.getLogger(VIII_ShaderManager.class);

    private VIII_ShaderManager() {
        this.checkOpenGLSupport();
    }

    public static VIII_ShaderManager getInstance() {
        if (instance == null) {
            instance = new VIII_ShaderManager();
        }

        return instance;
    }

    private void checkOpenGLSupport() {
        try {
            this.openGL20Supported = GLContext.getCapabilities().OpenGL20;
            LOG.info("[MOCI_SHADER] OpenGL20Supported=" + this.openGL20Supported);
        } catch (Exception e) {
            this.openGL20Supported = false;
            LOG.warn("[MOCI_SHADER] OpenGL capability check failed", e);
        }

    }

    public int createShader(String vertPath, String fragPath, String shaderKey) {
        if (!this.openGL20Supported) {
            LOG.warn("[MOCI_SHADER] Skip createShader (OpenGL 2.0 not supported)");
            return 0;
        } else if (this.shaders.containsKey(shaderKey)) {
            LOG.info("[MOCI_SHADER] Reuse program key=" + shaderKey + " id=" + this.shaders.get(shaderKey));
            return (Integer)this.shaders.get(shaderKey);
        } else {
            try {
                String vertSource = Global.getSettings().loadText(vertPath);
                String fragSource = Global.getSettings().loadText(fragPath);
                LOG.info("[MOCI_SHADER] Load sources vert=" + vertPath + " len=" + (vertSource != null ? vertSource.length() : -1) + ", frag=" + fragPath + " len=" + (fragSource != null ? fragSource.length() : -1));
                int programID = this.createShaderProgram(vertSource, fragSource);
                if (programID != 0) {
                    this.shaders.put(shaderKey, programID);
                    LOG.info("[MOCI_SHADER] Program linked key=" + shaderKey + " id=" + programID);
                    return programID;
                } else {
                    LOG.warn("[MOCI_SHADER] Program link failed key=" + shaderKey);
                    return 0;
                }
            } catch (Exception e) {
                LOG.error("[MOCI_SHADER] Exception while creating program key=" + shaderKey, e);
                return 0;
            }
        }
    }

    public int getShader(String shaderKey) {
        Integer shader = (Integer)this.shaders.get(shaderKey);
        return shader != null ? shader : 0;
    }

    public boolean isShaderValid(String shaderKey) {
        Integer shaderID = (Integer)this.shaders.get(shaderKey);
        if (shaderID != null && shaderID != 0) {
            try {
                return GL20.glIsProgram(shaderID);
            } catch (Exception var4) {
                return false;
            }
        } else {
            return false;
        }
    }

    private int createShader(String source, int shaderType) {
        int shaderID = GL20.glCreateShader(shaderType);
        GL20.glShaderSource(shaderID, source);
        GL20.glCompileShader(shaderID);
        if (GL20.glGetShaderi(shaderID, 35713) == 0) {
            String log = GL20.glGetShaderInfoLog(shaderID, GL20.glGetShaderi(shaderID, 35716));
            LOG.error("[MOCI_SHADER] Compile failed type=" + shaderType + " log=\n" + log);
            GL20.glDeleteShader(shaderID);
            return 0;
        } else {
            LOG.info("[MOCI_SHADER] Compile OK type=" + shaderType + " id=" + shaderID);
            return shaderID;
        }
    }

    private int createShaderProgram(String vertSource, String fragSource) {
        int programID = GL20.glCreateProgram();
        int vertShader = this.createShader(vertSource, 35633);
        int fragShader = this.createShader(fragSource, 35632);
        if (vertShader != 0 && fragShader != 0) {
            GL20.glAttachShader(programID, vertShader);
            GL20.glAttachShader(programID, fragShader);
            GL20.glLinkProgram(programID);
            if (GL20.glGetProgrami(programID, 35714) == 0) {
                String error = GL20.glGetProgramInfoLog(programID, GL20.glGetProgrami(programID, 35716));
                Global.getLogger(this.getClass()).error("Shader program linking failed:\n" + error);
                LOG.error("[MOCI_SHADER] Link failed log=\n" + error);
                GL20.glDeleteProgram(programID);
                GL20.glDeleteShader(vertShader);
                GL20.glDeleteShader(fragShader);
                return 0;
            } else {
                LOG.info("[MOCI_SHADER] Link OK program=" + programID + ", vert=" + vertShader + ", frag=" + fragShader);
                GL20.glDeleteShader(vertShader);
                GL20.glDeleteShader(fragShader);
                return programID;
            }
        } else {
            Global.getLogger(this.getClass()).error("Failed to compile shaders - vertex: " + vertShader + ", fragment: " + fragShader);
            if (vertShader != 0) {
                GL20.glDeleteShader(vertShader);
            }

            if (fragShader != 0) {
                GL20.glDeleteShader(fragShader);
            }

            GL20.glDeleteProgram(programID);
            return 0;
        }
    }

    public void cleanup() {
        for(Map.Entry<String, Integer> entry : this.shaders.entrySet()) {
            try {
                GL20.glDeleteProgram((Integer)entry.getValue());
            } catch (Exception var4) {
            }
        }

        this.shaders.clear();
    }

    public boolean isOpenGL20Supported() {
        return this.openGL20Supported;
    }

    public void printShaderStats() {
    }
}
