export default [
    {
        path: '/',
        redirect: '/varvis/chromosome-visualization',
    },
    {
        path: '/varvis',
        routes: [
            {
                path: '/varvis',
                redirect: '/varvis/chromosome-visualization',
            },
            {
                path: '/varvis/group-demo',
                component: './varvis/GroupDemo',
            },
            {
                path: '/varvis/chromosome-visualization',
                component: './varvis/ChromosomeVisualization',
            },
        ],
    },
    {
        path: '*',
        redirect: '/varvis/chromosome-visualization',
    },
];
