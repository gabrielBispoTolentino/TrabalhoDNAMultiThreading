/*
 * Busca de motivos com até maxMis divergências (distância de Hamming).
 * UM work-item por posição inicial da sequência (dentro do trecho enviado ao device).
 * Cada work-item compara todos os padrões a partir da sua posição e, por cada
 * ocorrência, reserva uma entrada no buffer de saída com atomic_inc.
 */
__kernel void motif_search(
        __global const uchar* seq,      /* trecho da sequência                         */
        const int chunkLen,             /* bytes válidos em seq                        */
        const int numStarts,            /* posições iniciais de que este trecho é dono */
        __constant uchar* pats,         /* padrões concatenados                        */
        __constant int* patOff,         /* deslocamento de cada padrão em pats         */
        __constant int* patLen,         /* tamanho de cada padrão                      */
        const int numPats,
        const int maxMis,
        __global int* counter,          /* nº de ocorrências encontradas               */
        __global int* outPos,
        __global int* outPat,
        __global uchar* outMis,
        const int capacity)
{
    int i = get_global_id(0);
    if (i >= numStarts) return;

    for (int p = 0; p < numPats; p++) {
        int len = patLen[p];
        if (i + len > chunkLen) continue;
        int off = patOff[p];
        int mis = 0;
        for (int k = 0; k < len; k++) {
            uchar b = seq[i + k];
            if (b != pats[off + k] || b == 'N') {   /* N conta sempre como divergência */
                if (++mis > maxMis) break;
            }
        }
        if (mis <= maxMis) {
            int idx = atomic_inc(counter);
            if (idx < capacity) {          /* se encher, o host repete com mais espaço */
                outPos[idx] = i;
                outPat[idx] = p;
                outMis[idx] = (uchar) mis;
            }
        }
    }
}
