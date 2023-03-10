struct LightSource
{
    bool  infinite;  // Indicates that light is at infinity so only direction matters.
    vec3  position;
    vec3  direction;
    vec3  ambient;
    vec3  diffuse;
    vec3  specular;
    float spotExponent;
    float spotCutoff;
    float attenuation0;
    float attenuation1;
    float attenuation2;
};
uniform LightSource light[8];
uniform int enabled;  // how many lights are on

struct Material
{
    vec3  ambient;
    vec4  diffuse;
    vec3  emission;
    vec3  specular;
    float shininess;
};
uniform Material material;

varying vec3 vN; // normal direction
varying vec3 vP; // position in eye space

void main()
{
    vec3 N = normalize (vN);
    vec3 V = normalize (-vP);

    vec3 color = vec3 (0.0);
    for (int i = 0; i < enabled; i++)
    {
        vec3 L; // direction from vertex to light source
        float distance;
        if (light[i].infinite)
        {
            L = -light[i].direction;
        }
        else
        {
            L = light[i].position - vP;
            distance = length (L);
            L /= distance;  // normalize
        }

        float diffuseFactor  = max (0, dot (N, L));  // Lambertian reflection
        float specularFactor = 0;
        if (diffuseFactor > 0)
        {
            // Phong specularity
            vec3  R     = reflect (-L, N);
            float angle = dot (R, V);
            if (angle > 0) specularFactor = pow (angle, material.shininess);
        }

        float attenuation = 1;
        if (! light[i].infinite) attenuation = light[i].attenuation0 + (light[i].attenuation1 + light[i].attenuation2 * distance) * distance;
        color +=      material.emission;
        color +=      material.ambient  * light[i].ambient;
        color += vec3(material.diffuse) * light[i].diffuse  * diffuseFactor  / attenuation;
        color +=      material.specular * light[i].specular * specularFactor / attenuation;
    }

    gl_FragColor = vec4(clamp (color, 0, 1), material.diffuse.a);
}
